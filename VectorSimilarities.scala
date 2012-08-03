package me.echen.scaldingale

import com.twitter.scalding._

import cascading.pipe.Pipe

/**
 * Given a dataset of ratings, how can we compute the similarity 
 * between pairs of items?
 *
 * This class defines an abstract ratings input format. Subclasses
 * that provide a concrete implementation of the input (in the form of
 * a tuple stream containing: a user, the item being rated, and the numeric
 * rating of the item by the user) will automatically calculate
 * similarities of items.
 *
 * In more detail, each item is represented as a (sparse) vector of all
 * its ratings. Similarity measures (such as correlation, cosine similarity,
 * and Jaccard similarity) are then applied to these vectors.
 *
 * @author Edwin Chen
 */
abstract class VectorSimilarities(args : Args) extends Job(args) {
  
  /**
   * Parameters to regularize correlation.
   */
  val PRIOR_COUNT = 10
  val PRIOR_CORRELATION = 0
  
  /**
   * Filters to speed up computation and reduce noise.
   * Subclasses should probably override these, based on the actual data.
   */
  val MIN_NUM_RATERS = 3
  val MAX_NUM_RATERS = 10000
  val MIN_INTERSECTION = 50

  /**
   * Subclasses should override this to define their own input.
   */
  def input(userField : Symbol, itemField : Symbol, ratingField : Symbol) : Pipe
  
// *************************
// * STEPS OF THE COMPUTATION
// *************************  

  /**
   * Read in the input and give each field a type and name.
   */
  val ratings = input('user, 'item, 'rating)  

  /**
   * Also keep track of the total number of people who rated an item.
   */
  val ratingsWithSize =
    ratings
      // Put the size of each group in a field called "numRaters".  
      .groupBy('item) { _.size('numRaters) }
      // Rename, since Scalding currently requires both sides of a join to have distinctly named fields.
      .rename('item -> 'itemX)
      .joinWithLarger('itemX -> 'item, ratings).discard('itemX)
      .filter('numRaters) { numRaters : Long => numRaters >= MIN_NUM_RATERS && numRaters <= MAX_NUM_RATERS }

  /**
   * Make a dummy copy of the ratings, so we can do a self-join.
   */
  val ratings2 = 
    ratingsWithSize
      .rename(('user, 'item, 'rating, 'numRaters) -> ('user2, 'item2, 'rating2, 'numRaters2))

  /**
   * Join the two rating streams on their user fields, 
   * in order to find all pairs of items that a user has rated.  
   */
  val ratingPairs =
    ratingsWithSize
      .joinWithSmaller('user -> 'user2, ratings2)
      // De-dupe so that we don't calculate similarity of both (A, B) and (B, A).
      .filter('item, 'item2) { items : (String, String) => items._1 < items._2 }
      .project('item, 'rating, 'numRaters, 'item2, 'rating2, 'numRaters2)

  /**
   * Compute dot products, norms, sums, and sizes of the rating vectors.
   */
  val vectorCalcs =
    ratingPairs
      // Compute (x*y, x^2, y^2), which we need for dot products and norms.
      .map(('rating, 'rating2) -> ('ratingProd, 'ratingSq, 'rating2Sq)) {
        ratings : (Double, Double) =>
        (ratings._1 * ratings._2, scala.math.pow(ratings._1, 2), scala.math.pow(ratings._2, 2))
      }
      .groupBy('item, 'item2) { 
  			_
  			  .size
  				.sum('ratingProd -> 'dotProduct)
  				.sum('rating -> 'ratingSum)
  				.sum('rating2 -> 'rating2Sum)
  				.sum('ratingSq -> 'ratingNormSq)
  				.sum('rating2Sq -> 'rating2NormSq)
  				.max('numRaters) // Simply an easy way to make sure the numRaters field stays.
  				.max('numRaters2)
  		}
  		.filter('size) { size : Long => size >= MIN_INTERSECTION }

  /**
   * Calculate similarity between rating vectors using similarity measures
   * like correlation, cosine similarity, and Jaccard similarity.
   */
  val similarities =
    vectorCalcs
      .map(('size, 'dotProduct, 'ratingSum, 'rating2Sum, 'ratingNormSq, 'rating2NormSq, 'numRaters, 'numRaters2) -> 
        ('correlation, 'regularizedCorrelation, 'cosineSimilarity, 'jaccardSimilarity)) {
          
        fields : (Double, Double, Double, Double, Double, Double, Double, Double) =>
                
        val (size, dotProduct, ratingSum, rating2Sum, ratingNormSq, rating2NormSq, numRaters, numRaters2) = fields
        
        val corr = correlation(size, dotProduct, ratingSum, rating2Sum, ratingNormSq, rating2NormSq)
        val regCorr = regularizedCorrelation(size, dotProduct, ratingSum, rating2Sum, ratingNormSq, rating2NormSq, PRIOR_COUNT, PRIOR_CORRELATION)
        val cosSim = cosineSimilarity(dotProduct, scala.math.sqrt(ratingNormSq), scala.math.sqrt(rating2NormSq))
        val jaccard = jaccardSimilarity(size, numRaters, numRaters2)
        
        (corr, regCorr, cosSim, jaccard)
      }

  /**
   * Output all similarities to a TSV file.
   */
  similarities
    .project('item, 'item2, 'correlation, 'regularizedCorrelation, 'cosineSimilarity, 'jaccardSimilarity, 'size, 'numRaters, 'numRaters2)
    .write(Tsv(args("output")))
  
// *************************
// * SIMILARITY MEASURES
// *************************
  
  /**
   * The correlation between two vectors A, B is
   *   cov(A, B) / (stdDev(A) * stdDev(B))
   *
   * This is equivalent to
   *   [n * dotProduct(A, B) - sum(A) * sum(B)] / 
   *     sqrt{ [n * norm(A)^2 - sum(A)^2] [n * norm(B)^2 - sum(B)^2] }
   */
  def correlation(size : Double, dotProduct : Double, ratingSum : Double, 
    rating2Sum : Double, ratingNormSq : Double, rating2NormSq : Double) = {
      
    val numerator = size * dotProduct - ratingSum * rating2Sum
    val denominator = scala.math.sqrt(size * ratingNormSq - ratingSum * ratingSum) * scala.math.sqrt(size * rating2NormSq - rating2Sum * rating2Sum)
    
    numerator / denominator
  }
  
  /**
   * Regularize correlation by adding virtual pseudocounts over a prior:
   *   RegularizedCorrelation = w * ActualCorrelation + (1 - w) * PriorCorrelation
   * where w = # actualPairs / (# actualPairs + # virtualPairs).
   */
  def regularizedCorrelation(size : Double, dotProduct : Double, ratingSum : Double, 
    rating2Sum : Double, ratingNormSq : Double, rating2NormSq : Double, 
    virtualCount : Double, priorCorrelation : Double) = {

    val unregularizedCorrelation = correlation(size, dotProduct, ratingSum, rating2Sum, ratingNormSq, rating2NormSq)
    val w = size / (size + virtualCount)

    w * unregularizedCorrelation + (1 - w) * priorCorrelation
  }  

  /**
   * The cosine similarity between two vectors A, B is
   *   dotProduct(A, B) / (norm(A) * norm(B))
   */
  def cosineSimilarity(dotProduct : Double, ratingNorm : Double, rating2Norm : Double) = {
    dotProduct / (ratingNorm * rating2Norm)
  }

  /**
   * The Jaccard Similarity between two sets A, B is
   *   |Intersection(A, B)| / |Union(A, B)|
   */
  def jaccardSimilarity(usersInCommon : Double, totalUsers1 : Double, totalUsers2 : Double) = {
    val union = totalUsers1 + totalUsers2 - usersInCommon
    usersInCommon / union
  }  
}
