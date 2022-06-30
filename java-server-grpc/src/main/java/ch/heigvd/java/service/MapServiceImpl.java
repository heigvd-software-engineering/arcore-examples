package ch.heigvd.java.service;

import ch.heigvd.java.map.LocalRestaurant;
import ch.heigvd.java.map.LocalRestaurantReply;
import ch.heigvd.java.map.LocalRestaurantRequest;
import ch.heigvd.java.map.MapServiceGrpc.MapServiceImplBase;
import ch.heigvd.java.server.MapServer;
import io.grpc.stub.StreamObserver;
import java.util.logging.Logger;

public class MapServiceImpl extends MapServiceImplBase {
  private static final Logger logger = Logger.getLogger(MapServiceImpl.class.getName());
  @Override
  public void getLocalRestaurant(LocalRestaurantRequest req, StreamObserver<LocalRestaurantReply> responseObserver) {
    LocalRestaurant localRestaurant = LocalRestaurant.newBuilder()
        .setAltitude(500)
        .setLatitude(46.764511)
        .setLongitude(6.646472)
        .setName("Restaurant sympa")
        .setOpeningHour("11:00 - 22:00")
        .build();
    LocalRestaurantReply reply = LocalRestaurantReply.newBuilder().addLocalRestaurant(localRestaurant).build();
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }
}
