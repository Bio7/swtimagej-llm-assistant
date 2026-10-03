Put the SWTImageJ bundle jar here as:

    lib/org.eclipse.swt.imagej.jar

Where to get it:
  * SWTImageJ installation: folder "plugins", file org.eclipse.swt.imagej_<version>.jar
    (copy it here and rename it, or point the build to it with -Dswtimagej.jar=...)
  * SWTImageJ built from source (https://github.com/eclipse-swtimagej/SWTImageJ):
    mvn clean verify  ->  org.eclipse.swt.imagej/target/org.eclipse.swt.imagej-<version>.jar

The jar is only used for compiling; it is not packaged into LLM_Assistant.jar.
