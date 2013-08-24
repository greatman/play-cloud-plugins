package plugins.cloudimage

import play.api.Application
import play.api.Logger
import play.api.i18n.Messages
import play.api.libs.json.Json

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.ByteArrayBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.DefaultHttpClient

class CloudinaryCloudImagePlugin(app: Application) extends CloudImagePlugin {
	private val parsingRegex = """cloudinary://(.*)[:](.*)[@](.*)""".r

	private lazy val cloudImageServiceInstance: CloudImageService = {
		val confUrl = app.configuration.getString("cloudinary.url").getOrElse {
			throw new RuntimeException("CLOUDINARY_URL not set")
		}
		confUrl match {
			case "mock" => new MockCloudImageService()
			case _ => {
				val parsingRegex(apiKey, secretKey, cloudName) = confUrl

				Logger.debug(String.format("CloudinaryCloudImageService about to be created: api_key: %s, secret_key: %s, cloud_name: %s", apiKey, secretKey, cloudName))

				new CloudinaryCloudImageService(apiKey, secretKey, cloudName)
			}
		}
	}

	def cloudImageService = cloudImageServiceInstance
}

class CloudinaryCloudImageService(apiKey: String, secretKey: String, cloudName: String) extends CloudImageService {
	private val UPLOAD_URL_PATTERN = "http://api.cloudinary.com/v1_1/%s/image/upload"
	private val DESTROY_URL_PATTERN = "http://api.cloudinary.com/v1_1/%s/image/destroy"
	private val TRANSFORMATION_WIDTH = "w_"
	private val TRANSFORMATION_HEIGHT = "h_"

	private val uploadUrl = UPLOAD_URL_PATTERN.format(cloudName)
	private val destroyUrl = DESTROY_URL_PATTERN.format(cloudName)

	def upload(filename: String, fileInBytes: Array[Byte]): CloudImageResponse = {
		Logger.debug(String.format("CloudImageService - upload - start"))

		val httpClient = new DefaultHttpClient
		val post = new HttpPost(uploadUrl)
		val timestamp = scala.compat.Platform.currentTime.toString();
		val multiPartEntity: MultipartEntity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE)
		val signature = "timestamp=" + timestamp + secretKey

		multiPartEntity.addPart("file", new ByteArrayBody(fileInBytes, filename))
		multiPartEntity.addPart("timestamp", new StringBody(timestamp))
		multiPartEntity.addPart("api_key", new StringBody(apiKey))

		val signedParams: String = DigestUtils.shaHex(signature)
		multiPartEntity.addPart("signature", new StringBody(signedParams))
		post.setEntity(multiPartEntity)

		val r: HttpResponse = httpClient.execute(post)
		val responseBody = IOUtils.toString(r.getEntity().getContent());
		Logger.debug("cloudinary - upload image - response: " + responseBody)

		val jsonResponse = Json.parse(responseBody)

		val error = (jsonResponse \ "error" \ "message").asOpt[String]
		if (error.isEmpty) {
			val publicId = (jsonResponse \ "public_id").asOpt[String]

			if (publicId.isDefined) {
				new CloudImageSuccessResponse(
					url = (jsonResponse \ "url").as[String],
					secureUrl = (jsonResponse \ "secure_url").as[String],
					publicId = publicId.get,
					version = (jsonResponse \ "version").as[Long].toString,
					width = (jsonResponse \ "width").as[Long].toString,
					height = (jsonResponse \ "height").as[Long].toString,
					format = (jsonResponse \ "format").as[String],
					resourceType = (jsonResponse \ "resource_type").as[String],
					signature = (jsonResponse \ "signature").as[String])

			} else {
				new CloudImageErrorResponse(message = Messages("error.unknown"))
			}
		} else {
			new CloudImageErrorResponse(message = error.get)
		}
	}

	def destroy(publicId: String): CloudImageResponse = {

		Logger.debug(String.format("CloudImageService - destroy, publicId= %s", publicId))

		val httpClient = new DefaultHttpClient
		val post = new HttpPost(destroyUrl)
		val timestamp = scala.compat.Platform.currentTime.toString()
		val multiPartEntity: MultipartEntity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE)
		val signature = "public_id=" + publicId + "&timestamp=" + timestamp + secretKey

		multiPartEntity.addPart("public_id", new StringBody(publicId))
		multiPartEntity.addPart("timestamp", new StringBody(timestamp))
		multiPartEntity.addPart("api_key", new StringBody(apiKey))

		val signedParams: String = DigestUtils.shaHex(signature)
		multiPartEntity.addPart("signature", new StringBody(signedParams))
		post.setEntity(multiPartEntity)

		val r: HttpResponse = httpClient.execute(post)

		val responseBody = IOUtils.toString(r.getEntity().getContent());
		Logger.debug("cloudinary - destroy image - response: " + responseBody)

		val jsonResponse = Json.parse(responseBody)

		val error = (jsonResponse \ "error" \ "message").asOpt[String]
		if (error.isEmpty) {
			new CloudImageDestroySuccessResponse(
				result = (jsonResponse \ "result").as[String])
		} else {
			new CloudImageErrorResponse(message = error.get)
		}
	}

	def getTransformationUrl(url: String, properties: Map[TransformationProperty.Value, String]): String = {
		val after = StringUtils.substringAfterLast(url, "upload/")
		val before = StringUtils.substringBeforeLast(url, after)

		val sb = new StringBuilder();
		var first = false;

		properties.get(TransformationProperty.WIDTH) match {
			case Some(x) =>
				if (!StringUtils.isEmpty(x)) {
					sb.append(TRANSFORMATION_WIDTH).append(x)
					first = true;
				}
			case _ =>
		}

		properties.get(TransformationProperty.HEIGHT) match {
			case Some(x) =>
				if (!StringUtils.isEmpty(x)) {
					if (first) sb.append(",")
					else first = true
					sb.append(TRANSFORMATION_HEIGHT).append(x)
				}
			case _ =>
		}

		properties.get(TransformationProperty.CROP_MODE) match {
			case Some(x) =>
				if (first) sb.append(",")
				else first = true
				if (!StringUtils.isEmpty(x)) {
					sb.append(x)
				}
			case _ =>
		}

		before + sb.toString() + "/" + after;
	}
}
