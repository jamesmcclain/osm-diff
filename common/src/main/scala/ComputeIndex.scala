package osmdiff

import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._

object ComputeIndex {

  private val logger = {
    val logger = Logger.getLogger(this.getClass)
    logger.setLevel(Level.INFO)
    logger
  }

  private def reset(edges: DataFrame): DataFrame = { // XXX optimization barrier question
    edges
      .select(
        col("ap"), col("aid"), col("atype"),
        col("instant"),
        col("bp"), col("bid"), col("btype"),
        lit(0L).as("iteration"),
        col("extra"))
  }

  private def mirror(edges: DataFrame): DataFrame = {
    edges
      .select(
        col("bp").as("ap"), col("bid").as("aid"), col("btype").as("atype"),
        col("instant"),
        col("ap").as("bp"), col("aid").as("bid"), col("atype").as("btype"),
        lit(0L).as("iteration"),
        lit(true).as("extra"))
  }

  def transitiveStep(
    leftEdges: DataFrame, rightEdges: DataFrame, iteration: Long
  ): DataFrame = {
    logger.info(s"Transitive closure iteration $iteration")
    leftEdges
      .filter((col("iteration") === iteration-1) && (col("extra") === false))
      .as("left")
      .join(
      rightEdges.as("right"),
        ((col("left.bp") === col("right.ap")) && // Try to use partition pruning (may get better in some future version)
          (col("left.bid") === col("right.aid") && col("left.btype") === col("right.atype")) && // The two edges meet
          (col("left.atype") =!= lit("way") || col("right.btype") =!= lit("way")) && // Do no join way to way
          (col("left.atype") =!= lit("node") || col("right.btype") =!= lit("node")) && // Do no join node to node
          (col("left.aid") =!= col("right.bid") || col("left.atype") =!= col("right.btype"))), // Do not join something to itself
        "inner")
      .select(
        col("left.ap").as("ap"), col("left.aid").as("aid"), col("left.atype").as("atype"),
        Common.larger(col("left.instant"), col("right.instant")).as("instant"),
        col("right.bp").as("bp"), col("right.bid").as("bid"), col("right.btype").as("btype"),
        lit(iteration).as("iteration"),
        lit(false).as("extra"))
  }

  def apply(
    rows: DataFrame,
    previousEdgesOption: Option[DataFrame]
  ): DataFrame = {
    logger.info(s"Index (Spark)")

    val initialEdges = Common.edgesFromRows(rows).select(Common.edgeColumns: _*)

    var additionalEdges = initialEdges
    var previousEdges = (previousEdgesOption match {
      case Some(edges) => edges.select(Common.edgeColumns: _*).union(initialEdges)
      case None => initialEdges
    }).select(Common.edgeColumns: _*)
    var iteration = 1L
    var keepGoing = false

    do {
      val newEdges = transitiveStep(previousEdges, initialEdges, iteration)
        .select(Common.edgeColumns: _*)
      previousEdges = previousEdges.union(newEdges).select(Common.edgeColumns: _*)
      additionalEdges = additionalEdges.union(newEdges).select(Common.edgeColumns: _*)
      iteration = iteration + 1L
      keepGoing = (iteration < 7) && (!newEdges.rdd.isEmpty)
    } while (keepGoing)

    reset(additionalEdges).select(Common.edgeColumns: _*)
      .union(mirror(additionalEdges).select(Common.edgeColumns: _*))
  }

}
