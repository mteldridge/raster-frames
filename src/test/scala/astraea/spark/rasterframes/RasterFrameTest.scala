

package astraea.spark.rasterframes

import com.typesafe.scalalogging.LazyLogging
import geotrellis.proj4.LatLng
import geotrellis.raster.render.{ColorMap, ColorRamp}
import geotrellis.raster.{ProjectedRaster, Tile, TileFeature, TileLayout}
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.tiling._
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

/**
 * RasterFrame test rig.
 *
 * @author sfitch 
 * @since 7/10/17
 */
class RasterFrameTest extends TestEnvironment with TestData with LazyLogging {
  import TestData.randomTile
  import spark.implicits._

  describe("RasterFrame") {
    it("should implicitly convert from layer type") {

      val tileLayerRDD = TestData.randomTileLayerRDD(20, 20, 2, 2)

      val rf = tileLayerRDD.toRF

      assert(rf.tileColumns.nonEmpty)
      assert(rf.spatialKeyColumn.toString() == "spatial_key")

      assert(rf.schema.head.metadata.contains(CONTEXT_METADATA_KEY))
      assert(rf.schema.head.metadata.json.contains("tileLayout"))

      assert(
        rf.select(tileDimensions($"tile"))
          .as[Tuple1[(Int, Int)]]
          .map(_._1)
          .collect()
          .forall(_ == (10, 10))
      )

      assert(rf.count() === 4)
    }

    it("should implicitly convert layer of TileFeature") {
      val tile = TileFeature(randomTile(20, 20, "uint8"), (1, "b", 3.0))

      val tileLayout = TileLayout(1, 1, 20, 20)

      val layoutScheme = FloatingLayoutScheme(tileLayout.tileCols, tileLayout.tileRows)
      val inputRdd = sc.parallelize(Seq((ProjectedExtent(LatLng.worldExtent, LatLng), tile)))

      val (_, metadata) = inputRdd.collectMetadata[SpatialKey](LatLng, layoutScheme)

      val tileRDD = inputRdd.map {case (k, v) ⇒ (metadata.mapTransform(k.extent.center), v)}

      val tileLayerRDD = TileFeatureLayerRDD(tileRDD, metadata)

      val rf = WithTFContextRDDMethods(tileLayerRDD).toRF

      assert(rf === rf)
    }

    it("should convert a GeoTiff to RasterFrame") {
      val praster: ProjectedRaster[Tile] = sampleGeoTiff.projectedRaster
      val (cols, rows) = praster.raster.dimensions

      val layoutCols = math.ceil(cols / 128.0).toInt
      val layoutRows = math.ceil(rows / 128.0).toInt

      assert(praster.toRF.count() === 1)
      assert(praster.toRF(128, 128).count() === (layoutCols * layoutRows))
    }

    it("should provide TileLayerMetadata") {
      val rf = sampleGeoTiff.projectedRaster.toRF(256, 256)
      val tlm = rf.tileLayerMetadata
      assert(tlm.bounds.get._1 === SpatialKey(0, 0))
      assert(tlm.bounds.get._2 === SpatialKey(3, 1))
    }

    it("should clip TileLayerMetadata extent") {
      val tiled = sampleTileLayerRDD

      val rf = tiled.toRF

      val worldish = Extent(-179, -89, 179, 89)
      val areaish = Extent(-90, 30, -81, 39)

      val orig = tiled.metadata.extent
      assert(orig.contains(worldish))
      assert(orig.contains(areaish))

      val clipped = rf.clipLayerExtent.tileLayerMetadata.extent
      assert(!clipped.contains(worldish))
      assert(clipped.contains(areaish))
    }

    def basicallySame(expected: Extent, computed: Extent): Unit = {
      val components = Seq(
        (expected.xmin, computed.xmin),
        (expected.ymin, computed.ymin),
        (expected.xmax, computed.xmax),
        (expected.ymax, computed.ymax)
      )
      forEvery(components)(c ⇒
        assert(c._1 === c._2 +- 0.000001)
      )
    }

    it("shouldn't clip already clipped extents") {
      val rf = TestData.randomTileLayerRDD(1024, 1024, 8, 8).toRF

      val expected = rf.tileLayerMetadata.extent
      val computed = rf.clipLayerExtent.tileLayerMetadata.extent
      basicallySame(expected, computed)

      val pr = sampleGeoTiff.projectedRaster
      val rf2 = pr.toRF(256, 256)
      val expected2 = rf2.tileLayerMetadata.extent
      val computed2 = rf2.clipLayerExtent.tileLayerMetadata.extent
      basicallySame(expected2, computed2)
    }

    def Greyscale(stops: Int): ColorRamp = {
      val colors = (0 to stops)
        .map(i ⇒ {
          val c = java.awt.Color.HSBtoRGB(0f, 0f, i / stops.toFloat)
          (c << 8) | 0xFF // Add alpha channel.
        })
      ColorRamp(colors)
    }

    def render(tile: Tile, tag: String): Unit = {
      if(!isCI) {
        val colors = ColorMap.fromQuantileBreaks(tile.histogram, Greyscale(128))
        val path = s"/tmp/${getClass.getSimpleName}_$tag.png"
        logger.info(s"Writing '$path'")
        tile.color(colors).renderPng().write(path)
      }
    }

    it("should restitch to raster") {
      // 774 × 500
      val praster: ProjectedRaster[Tile] = sampleGeoTiff.projectedRaster
      val (cols, rows) = praster.raster.dimensions
      val rf = praster.toRF(64, 64)
      val raster = rf.toRaster($"tile", cols, rows)

      render(raster.tile, "normal")
      assert(raster.raster.dimensions ===  (cols, rows))

      val smaller = rf.toRaster($"tile", cols/4, rows/4)
      render(smaller.tile, "smaller")
      assert(smaller.raster.dimensions ===  (cols/4, rows/4))

      val bigger = rf.toRaster($"tile", cols*4, rows*4)
      render(bigger.tile, "bigger")
      assert(bigger.raster.dimensions ===  (cols*4, rows*4))

      val squished = rf.toRaster($"tile", cols*5/4, rows*3/4)
      render(squished.tile, "squished")
      assert(squished.raster.dimensions === (cols*5/4, rows*3/4))
    }

    it("should restitch raster that's has derived tiles") {
      val praster: ProjectedRaster[Tile] = sampleGeoTiff.projectedRaster
      val rf = praster.toRF(64, 64)

      val equalizer = udf((t: Tile) => t.equalize())

      val equalized = rf.select(equalizer($"tile") as "equalized")

      intercept[IllegalArgumentException] {
        // spatial_key is lost
        equalized.asRF.toRaster($"equalized", 128, 128)
      }
    }
  }
}
