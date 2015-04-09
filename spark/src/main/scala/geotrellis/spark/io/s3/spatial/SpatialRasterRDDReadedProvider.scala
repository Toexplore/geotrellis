package geotrellis.spark.io.s3.spatial

import geotrellis.spark._
import geotrellis.raster._
import geotrellis.spark.io._

import geotrellis.spark.io.s3._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import com.typesafe.scalalogging.slf4j._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsRequest, ObjectListing}
import geotrellis.index.zcurve._
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import com.amazonaws.auth.{AWSCredentialsProvider}

object SpatialRasterRDDReaderProvider extends RasterRDDReaderProvider[SpatialKey] with LazyLogging {
  def reader(credentialsProvider: AWSCredentialsProvider,layerMetaData: S3LayerMetaData)(implicit sc: SparkContext): FilterableRasterRDDReader[SpatialKey] =
    new FilterableRasterRDDReader[SpatialKey] {
      def read(layerId: LayerId, filterSet: FilterSet[SpatialKey]): RasterRDD[SpatialKey] = {        
        val bucket = layerMetaData.bucket
        val dir = layerMetaData.key
        val rasterMetaData = layerMetaData.rasterMetaData
        logger.debug(s"Loading $layerId from $dir")

        val ranges = 
          (for (filter <- filterSet.filters) yield {
            filter match {
              case SpaceFilter(b) =>
                Z2.zranges(Z2(b.colMin, b.rowMin), Z2(b.colMax, b.rowMax))
            }
          }).flatten

        val rdd: RDD[(SpatialKey, Tile)] = sc
          .parallelize(ranges)
          .mapPartitions{ iter =>
            val s3Client = new AmazonS3Client(credentialsProvider)            
            iter
              .flatMap{ range => listKeys(s3Client, bucket, dir, range) }
              .map{ case (key, path) =>
                val is = s3Client.getObject(bucket, path).getObjectContent
                val tile =
                  ArrayTile.fromBytes(
                    S3RecordReader.readInputStream(is),
                    rasterMetaData.cellType,
                    rasterMetaData.tileLayout.tileCols,
                    rasterMetaData.tileLayout.tileRows
                  )
                is.close()
                key -> tile     
              }
          }
        new RasterRDD(rdd, rasterMetaData)
      }
    }

  /* This needs to happen, in space-time case we will not be able to enumarate the keys */
  def listKeys(s3client: AmazonS3Client, bucket: String, key: String, range: (Long, Long)): Vector[(SpatialKey, String)] = {
    val (minKey, maxKey) = range
    val delim = "/"

    val request = new ListObjectsRequest()
      .withBucketName(bucket)
      .withPrefix(key)
      .withDelimiter(delim)
      .withMaxKeys(math.min((maxKey - minKey).toInt + 1, 1024))
    
    val tileIdRx = """.+\/(\d+)""".r
    def readKeys(keys: Seq[String]): (Seq[(SpatialKey, String)], Boolean) = {
      val ret = ArrayBuffer.empty[(SpatialKey, String)]
      var endSeen = false
      keys.foreach{ key => 
        val tileIdRx(tileId) = key
        val index = new Z2(tileId.toLong)
        if (index.z >= minKey && index.z <= maxKey) 
          ret += SpatialKey(index.dim(0), index.dim(1)) -> key
        if (index.z >= maxKey)
          endSeen = true        
      }
      ret -> endSeen
    }

    var listing: ObjectListing = null
    var foundKeys: Vector[(SpatialKey, String)] = Vector.empty    
    var stop = false
    do {
      listing = s3client.listObjects(request)     
      
      // the keys could be either incomplete or include extra information, but in order.
      // need to decides if I ask for more or truncate the result      
      val (pairs, endSeen) = readKeys(listing.getObjectSummaries.asScala.map(_.getKey))
      foundKeys = foundKeys ++ pairs
      stop = endSeen
      request.setMarker(listing.getNextMarker)
    } while (listing.isTruncated && !stop)

    foundKeys
  }
}
