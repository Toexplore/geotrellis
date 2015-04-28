package geotrellis.spark.io.cassandra.spatial

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.cassandra._
import geotrellis.spark.io.index._
import geotrellis.spark.utils._
import geotrellis.raster._

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.querybuilder.QueryBuilder.{eq => eqs}

import scala.collection.JavaConversions._

import java.nio.ByteBuffer

object SpatialTileReaderProvider extends TileReaderProvider[SpatialKey] {

  def reader(layerId: LayerId, cassandraLayerMetaData: CassandraLayerMetaData, index: KeyIndex[SpatialKey])(implicit session: CassandraSession): Reader[SpatialKey, Tile] = {
    val CassandraLayerMetaData(rasterMetaData, _, _, tileTable) = cassandraLayerMetaData
    new Reader[SpatialKey, Tile] {
      def read(key: SpatialKey): Tile = {

        val indexer = index.toIndex(key).toString
        val query = QueryBuilder.select("value").from(session.keySpace, tileTable)
          .where (eqs("reverse_index", indexer.reverse))
          .and   (eqs("zoom", layerId.zoom))
          .and   (eqs("indexer", indexer))
          .and   (eqs("name", layerId.name))

        val results = session.execute(query)

        val size = results.getAvailableWithoutFetching
        val value = 
          if (size == 0) {
            sys.error(s"Tile with key $key not found for layer $layerId")
          } else if (size > 1) {
            sys.error(s"Multiple tiles found for $key for layer $layerId")
          } else {
            results.one.getBytes("value")
          }
        
        // TODO: Figure out deserialization error that forces unwrapping and rewrapping the ByteBuffer
        val byteArray = new Array[Byte](value.remaining)
        value.get(byteArray, 0, byteArray.length)

        val (_, tileBytes) = KryoSerializer.deserialize[(SpatialKey, Array[Byte])](ByteBuffer.wrap(byteArray))

        ArrayTile.fromBytes(
          tileBytes,
          rasterMetaData.cellType,
          rasterMetaData.tileLayout.tileCols,
          rasterMetaData.tileLayout.tileRows
        )
      }
    }
  }

}