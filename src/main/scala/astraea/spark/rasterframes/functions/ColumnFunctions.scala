/*
 * Copyright 2017 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package astraea.spark.rasterframes.functions

import geotrellis.raster.Tile
import geotrellis.raster.histogram.Histogram
import geotrellis.raster.mapalgebra.local.LocalTileBinaryOp
import geotrellis.raster.mapalgebra.{local ⇒ alg}
import geotrellis.raster.summary.Statistics
import org.apache.spark.annotation.Experimental
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.gt.Implicits._
import org.apache.spark.sql.gt._
import org.apache.spark.sql.types._

import scala.reflect.runtime.universe._

/**
 * UDFs for working with tiles in Spark DataFrames.
 *
 * @author sfitch
 * @since 4/3/17
 */
trait ColumnFunctions {
  private implicit val stringEnc: Encoder[String] = Encoders.STRING
  private implicit val doubleEnc: Encoder[Double] = Encoders.scalaDouble
  private implicit val statsEnc: Encoder[Statistics[Int]] = Encoders.product[Statistics[Int]]
  private implicit val longEnc: Encoder[Long] = Encoders.scalaLong

  // format: off
  /** Create a row for each cell in tile. */
  @Experimental
  def explodeTiles(cols: Column*): Column = explodeTileSample(1.0, cols: _*)

  /** Create a row for each cell in tile with random sampling. */
  @Experimental
  def explodeTileSample(sampleFraction: Double, cols: Column*): Column = {
    val exploder = ExplodeTileExpression(sampleFraction, cols.map(_.expr))
    // Hack to grab the first two non-cell columns
    val metaNames = exploder.elementSchema.fieldNames.take(2)
    val colNames = cols.map(_.columnName)
    new Column(exploder).as(metaNames ++ colNames)
  }

  /** Query the number of (cols, rows) in a tile. */
  @Experimental
  def tileDimensions(col: Column): Column = withAlias("tileDimensions", col)(
    udf[(Int, Int), Tile](UDFs.tileDimensions).apply(col)
  ).cast(StructType(Seq(StructField("cols", IntegerType), StructField("rows", IntegerType))))

  /**  Compute the full column aggregate floating point histogram. */
  @Experimental
  def aggHistogram(col: Column): TypedColumn[Any, Histogram[Double]] =
  withAlias("histogram", col)(
    UDFs.aggHistogram(col)
  ).as[Histogram[Double]]

  /** Compute the full column aggregate floating point statistics. */
  @Experimental
  def aggStats(col: Column): TypedColumn[Any, Statistics[Double]] =
  withAlias("stats", col)(
    UDFs.aggStats(col)
  ).as[Statistics[Double]]

  /** Compute tileHistogram of floating point tile values. */
  @Experimental
  def tileHistogramDouble(col: Column): TypedColumn[Any, Histogram[Double]] =
  withAlias("tileHistogramDouble", col)(
    udf[Histogram[Double], Tile](UDFs.tileHistogramDouble).apply(col)
  ).as[Histogram[Double]]

  /** Compute statistics of tile values. */
  @Experimental
  def tileStatsDouble(col: Column): TypedColumn[Any, Statistics[Double]] =
  withAlias("tileStatsDouble", col)(
    udf[Statistics[Double], Tile](UDFs.tileStatsDouble).apply(col)
  ).as[Statistics[Double]]

  /** Compute the tile-wise mean */
  @Experimental
  def tileMeanDouble(col: Column): TypedColumn[Any, Double] =
  withAlias("tileMeanDouble", col)(
    udf[Double, Tile](UDFs.tileMeanDouble).apply(col)
  ).as[Double]

  /** Compute the tile-wise mean */
  @Experimental
  def tileMean(col: Column): TypedColumn[Any, Double] =
  withAlias("tileMean", col)(
    udf[Double, Tile](UDFs.tileMean).apply(col)
  ).as[Double]

  /** Compute tileHistogram of tile values. */
  @Experimental
  def tileHistogram(col: Column): TypedColumn[Any, Histogram[Int]] =
  withAlias("tileHistogram", col)(
    udf[Histogram[Int], Tile](UDFs.tileHistogram).apply(col)
  ).as[Histogram[Int]]

  /** Compute statistics of tile values. */
  @Experimental
  def tileStats(col: Column): TypedColumn[Any, Statistics[Int]] =
  withAlias("tileStats", col)(
    udf[Statistics[Int], Tile](UDFs.tileStats).apply(col)
  ).as[Statistics[Int]]

  /** Counts the number of non-NoData cells per tile. */
  def dataCells(tile: Column): TypedColumn[Any, Long] =
    withAlias("dataCells", tile)(
      udf(UDFs.dataCells).apply(tile)
    ).as[Long]

  /** Counts the number of NoData cells per tile. */
  def nodataCells(tile: Column): TypedColumn[Any, Long] =
    withAlias("nodataCells", tile)(
      udf(UDFs.nodataCells).apply(tile)
    ).as[Long]

  /** Compute cell-local aggregate descriptive statistics for a column of tiles. */
  @Experimental
  def localAggStats(col: Column): Column =
  withAlias("localAggStats", col)(
    UDFs.localAggStats(col)
  )

  /** Compute the cellwise/local max operation between tiles in a column. */
  @Experimental
  def localAggMax(col: Column): TypedColumn[Any, Tile] =
  withAlias("localAggMax", col)(
    UDFs.localAggMax(col)
  ).as[Tile]

  /** Compute the cellwise/local min operation between tiles in a column. */
  @Experimental
  def localAggMin(col: Column): TypedColumn[Any, Tile] =
  withAlias("localAggMin", col)(
    UDFs.localAggMin(col)
  ).as[Tile]

  /** Compute the cellwise/local mean operation between tiles in a column. */
  @Experimental
  def localAggMean(col: Column): TypedColumn[Any, Tile] =
  withAlias("localAggMean", col)(
    UDFs.localAggMean(col)
  ).as[Tile]

  /** Compute the cellwise/local count of non-NoData cells for all tiles in a column. */
  @Experimental
  def localAggCount(col: Column): TypedColumn[Any, Tile] =
  withAlias("localCount", col)(
    UDFs.localAggCount(col)
  ).as[Tile]

  /** Cellwise addition between two tiles. */
  @Experimental
  def localAdd(left: Column, right: Column): TypedColumn[Any, Tile] =
  localAlgebra(alg.Add, left, right)

  /** Cellwise subtraction between two tiles. */
  @Experimental
  def localSubtract(left: Column, right: Column): TypedColumn[Any, Tile] =
  localAlgebra(alg.Subtract, left, right)

  /** Perform an arbitrary GeoTrellis `LocalTileBinaryOp` between two tile columns. */
  @Experimental
  def localAlgebra(op: LocalTileBinaryOp, left: Column, right: Column):
  TypedColumn[Any, Tile] =
    withAlias(opName(op), left, right)(
      udf[Tile, Tile, Tile](op.apply).apply(left, right)
    ).as[Tile]

  /** Render tile as ASCII string for debugging purposes. */
  @Experimental
  def renderAscii(col: Column): TypedColumn[Any, String] =
  withAlias("renderAscii", col)(
    udf[String, Tile](UDFs.renderAscii).apply(col)
  ).as[String]

  // --------------------------------------------------------------------------------------------
  // -- Private APIs below --
  // --------------------------------------------------------------------------------------------
  /** Tags output column with a nicer name. */
  private[rasterframes] def withAlias(name: String, inputs: Column*)(output: Column) = {
    val paramNames = inputs.map(_.columnName).mkString(",")
    output.as(s"$name($paramNames)")
  }

  private[rasterframes] def opName(op: LocalTileBinaryOp) =
    op.getClass.getSimpleName.replace("$", "").toLowerCase
}
