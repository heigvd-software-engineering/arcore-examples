# arcore-examples
A collection of ARcore examples.

## The examples

- [ArText](./ArText/) - An example of how to show a text with ArCore and SceneForm.
![](./demo-assets/ArText4-video.gif)
- [java-geospatial](./java-geospatial/) - An example of use of the Geospatial API of ArCore.
![](./demo-assets/Geospatial-video.gif)
  - [java-server-grpc](./java-server-grpc/) - A small grpc server to communicate with the java-geospatial (WIP).
  - [kubernetes](./kubernetes/) - A simple configuration for the grpc service on a kubernetes cluster.

## Problems and issues with ArCore the 17 August 2022

- Not a lot of documentation.
- Almost no examples or old ones.
- SceneForm was the main library to interacte in ArCore and is currently not supported by Google anymore. A fork is currently maintained by the community. ([Informations](https://stackoverflow.com/questions/62453399/google-sceneform-is-it-deprecated-any-replacement))
- Looks like they are waiting after Google Fragment to be more advance to improve ArCore.