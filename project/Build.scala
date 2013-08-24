import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {
	val appName = "play-cloud-plugins"
	val appVersion = "1.0.0-SNAPSHOT"

	val appDependencies = Seq(
		// Add your project dependencies here
		"net.databinder" %% "dispatch-http" % "0.8.10",
		"net.databinder" %% "dispatch-mime" % "0.8.10",
		"net.databinder" %% "dispatch-json" % "0.8.10"
	)

	val main = play.Project(appName, appVersion, appDependencies).settings(
		// Add your own project settings here
	)
}
