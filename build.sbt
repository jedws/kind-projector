name := "kind-projector"

organization := "org.spire-math"

version := "0.5.2"

scalaVersion := "2.10.3"

seq(bintrayResolverSettings: _*)

libraryDependencies <++= (scalaVersion) {
  v => Seq("org.scala-lang" % "scala-compiler" % v)
}

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked"
)

scalacOptions in console in Compile <+= (packageBin in Compile) map {
  pluginJar => "-Xplugin:" + pluginJar
}

scalacOptions in Test <+= (packageBin in Compile) map {
  pluginJar => "-Xplugin:" + pluginJar
}

crossScalaVersions := Seq("2.9.3", "2.11.0")

seq(bintrayPublishSettings: _*)

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
