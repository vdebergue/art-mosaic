import java.io._
import java.nio.file.Files
import scala.util.Try

import Main._
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.nio.JpegWriter
import com.softwaremill.sttp._
import com.softwaremill.sttp.okhttp.monix.OkHttpMonixBackend
import monix.eval.Task
import sun.misc.BASE64Encoder

object Main {
  val partSize = 100
  val origin = new File("data/The_Great_Wave_off_Kanagawa.jpg")

  def getMosaicPartTxt(part: File): File = {
    new File(s"data/mosaic/${part.getName}.txt")
  }

  def getSimilarImageFile(part: String, extension: String): File = {
    new File(s"data/mosaic/${part.stripSuffix(".txt")}-sim$extension")
  }

  def loadParts(): Seq[File] = {
    new File("data/parts").listFiles().filter(_.isFile)
  }
}

object SplitImageIntoParts {
  def main(args: Array[String]): Unit = {
    val img = Image.fromFile(origin)
    splitIntoParts(img)
  }

  def splitIntoParts(img: Image): Unit = {
    val imgWriter = JpegWriter()
    for {
      x <- 0 until img.width by partSize
      y <- 0 until img.height by partSize
    } {
      println(s"writing part $x-$y")
      val w = if (x + partSize > img.width) img.width - x else partSize
      val h = if (y + partSize > img.height) img.height - y else partSize
      val part = img.subimage(x, y, w, h)
      part.output(s"data/parts/part-$x-$y.jpg")(imgWriter)
    }
  }
}

object LookForSimilarParts {
  implicit lazy val backend = OkHttpMonixBackend()
  implicit lazy val scheduler = monix.execution.Scheduler.Implicits.global

  def main(args: Array[String]): Unit = {
    val parts = loadParts()
    val searchTasks = parts
      .filterNot(part => getMosaicPartTxt(part).exists())
      .grouped(5)
      .map(searchNewParts)
      .toList

    val writeParts = searchTasks.map{ task =>
      task.map { responses =>
        responses.foreach {
          case (part, response) =>
            val links = response.webDetection.visuallySimilarImages.getOrElse(Seq.empty).map(_.url)
            if (links.nonEmpty) {
              val txt = getMosaicPartTxt(part)
              val buffer = Files.newBufferedWriter(txt.toPath)
              val data = links.mkString("\n")
              buffer.write(data)
              buffer.close()
            }
        }
      }
    }

    println(s"${writeParts.size} tasks to do")
    val batches = writeParts.sliding(2, 2).map{ batch =>
      println(s"Running batch of size ${batch.size}")
      Task.gather(batch)
    }
    val aggregate = Task.sequence(batches).map(_.flatten.toList)

    implicit val opts = Task.defaultOptions
    val tasksDone = aggregate.runSyncUnsafe()
    println(s"Made ${tasksDone.size} tasks")
  }

  def searchNewParts(parts: Seq[File]): Task[Seq[(File, GoogleVision.AnnotateImageResponse)]] = {
    import GoogleVision._
    val googleApiKey = sys.env.getOrElse("GOOGLE_API_KEY", sys.error("Please set a GOOGLE_API_KEY"))
    sttp
      .post(uri"https://vision.googleapis.com/v1/images:annotate?key=$googleApiKey")
      .body(
        s"""{
           |"requests":[
           |${parts.map { part =>
          val bytes = Files.readAllBytes(part.toPath)
          val base64 = new BASE64Encoder().encode(bytes)
          s"""{
             |"image": {"content": "$base64"},
             |"features":[{
             |  "type": "WEB_DETECTION"
             |}]
             |}""".stripMargin
        }.mkString(",")}
           |]
           |}""".stripMargin
      )
      .response(circe.asJson[VisionResponse])
      .send()
      .flatMap { response =>
        val errorOrResponse = response.body match {
          case Left(body) => Left(new RuntimeException(s"Error from google: $body"))
          case Right(Left(error)) => Left(new RuntimeException(s"Json error: ${error}"))
          case Right(Right(r)) => Right(r)
        }
        Task.fromEither(errorOrResponse)
      }.map { response =>
      parts zip response.responses
    }
  }
}

object DownloadSimilarParts {
  import LookForSimilarParts.{backend, scheduler}

  val random = new scala.util.Random(1234)

  def main(args: Array[String]): Unit = {
    val similarFiles = loadParts().map { part =>
      val file = getMosaicPartTxt(part)
      if (!file.exists()) println(s"Missing similar file for part $part")
      file
    }.toList

    val downloadTasks = similarFiles.flatMap { file =>
      val content = new String(Files.readAllBytes(file.toPath))
      val lines = content.lines.toList
      if (lines.isEmpty) {
        println(s"No similar img found for $file")
        Seq.empty
      } else {
        Seq(file.getName -> lines)
      }
    }.map { case (part, links) =>
      val inJpg = getSimilarImageFile(part, ".jpg")
      val inPng = getSimilarImageFile(part, ".png")
      if (inJpg.exists()) Task.pure(inJpg)
      else if (inPng.exists()) Task.pure(inPng)
      else {
        val downloadTask: Task[File] = Task {
          val linkIndex = random.nextInt(links.size)
          links(linkIndex)
        }.flatMap { link =>
          downloadImage(part, link)
            .onErrorHandleWith { error =>
              println(s"Error with $link")
              Task.raiseError(error)
            }
        }

        downloadTask.onErrorRestart(5)
      }
    }

    val batches = downloadTasks.sliding(5, 5).map { batch =>
      println(s"Running batch of size ${batch.size}")
      Task.gather(batch)
    }

    val downloaded = Task.sequence(batches).map(_.flatten.toList).runSyncUnsafe()
    println(s"Finished ! Got ${downloaded.size} files")
  }

  private def downloadImage(part: String, link: String): Task[File] = {
    val extension = if (link.endsWith(".png")) ".png" else ".jpg"
    val fileOut = getSimilarImageFile(part, extension)

    Try(Uri(link)).map { uri =>
      sttp.get(uri"$link")
        .response(asFile(fileOut))
        .send()
        .flatMap { response =>
          Task.fromEither((error: String) => new RuntimeException(s"Could not download image $link for $part: $error"))(response.body)
        }
        .flatMap { file =>
          if (Try(Image.fromFile(file)).isSuccess) Task.pure(file)
          else Task.raiseError(new RuntimeException(s"Invalid image for $part"))
        }
    }.getOrElse {
      sys.error(s"Invalid Uri $link for $part")
    }
  }
}

object MakeMosaic {

  def main(args: Array[String]): Unit = {
    val img = Image.fromFile(origin).blank

    for {
      x <- (0 until img.width by partSize).reverse
      y <- 0 until img.height by partSize
    } {
      println(s"reading sim part $x-$y")
      val part = s"part-$x-$y.jpg"
      val similarImgFile = {
        val jpg = getSimilarImageFile(part, ".jpg")
        if (jpg.exists()) jpg else getSimilarImageFile(part, ".png")
      }
      val similarImg = Image.fromFile(similarImgFile)
      val w = if (x + partSize > img.width) img.width - x else partSize
      val h = if (y + partSize > img.height) img.height - y else partSize

      val scaledImg = similarImg.scaleTo(w, h)
      for {
        i <- 0 until scaledImg.width
        j <- 0 until scaledImg.height
      } {
        val pixel = scaledImg.pixel(i, j)
        img.setPixel(x + i, y + j, pixel)
      }
    }
    println("write output ...")
    img.output("data/output.jpg")(JpegWriter())
    println("Done !")
  }
}
