package geotrellis.spark.etl.file

import geotrellis.raster.Tile
import geotrellis.spark._
import geotrellis.spark.etl.config.EtlConf
import geotrellis.spark.io._
import geotrellis.spark.io.file._

import org.apache.spark.SparkContext

class SpatialFileOutput extends FileOutput[SpatialKey, Tile, TileLayerMetadata[SpatialKey]] {
  def writer(conf: EtlConf)(implicit sc: SparkContext) =
    FileLayerWriter(conf.output.params("path")).writer[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](conf.output.getKeyIndexMethod[SpatialKey])
}
