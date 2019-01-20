import io.circe.Decoder
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto._

object GoogleVision {

  case class VisionResponse(
                           responses: Seq[AnnotateImageResponse]
                           )

  case class AnnotateImageResponse(
                                    webDetection: WebDetection
                                  )

  case class WebDetection(
                           visuallySimilarImages: Option[Seq[WebImage]]
                                 )

  case class WebImage(
                     url: String
                     )

  implicit val decoderWebImage: Decoder[WebImage] = deriveDecoder
  implicit val decoderWebDetection: Decoder[WebDetection] = deriveDecoder
  implicit val decoderAnnotateImageResponse: Decoder[AnnotateImageResponse] = deriveDecoder
  implicit val decoder: Decoder[VisionResponse] = deriveDecoder
}
