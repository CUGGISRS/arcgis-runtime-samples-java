package com.esri.samples.map.generate_offline_map_overrides;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;

import com.esri.arcgisruntime.concurrent.Job;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.ServiceFeatureTable;
import com.esri.arcgisruntime.geometry.Envelope;
import com.esri.arcgisruntime.geometry.GeometryEngine;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.layers.Layer;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.portal.Portal;
import com.esri.arcgisruntime.portal.PortalItem;
import com.esri.arcgisruntime.security.AuthenticationManager;
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler;
import com.esri.arcgisruntime.symbology.SimpleLineSymbol;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateGeodatabaseParameters;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateLayerOption;
import com.esri.arcgisruntime.tasks.offlinemap.GenerateOfflineMapJob;
import com.esri.arcgisruntime.tasks.offlinemap.GenerateOfflineMapParameterOverrides;
import com.esri.arcgisruntime.tasks.offlinemap.GenerateOfflineMapParameters;
import com.esri.arcgisruntime.tasks.offlinemap.GenerateOfflineMapResult;
import com.esri.arcgisruntime.tasks.offlinemap.OfflineMapParametersKey;
import com.esri.arcgisruntime.tasks.offlinemap.OfflineMapTask;
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheParameters;

public class GenerateOfflineMapOverridesController {

  @FXML private MapView mapView;
  @FXML private Spinner<Integer> minScaleLevelSpinner;
  @FXML private Spinner<Integer> maxScaleLevelSpinner;
  @FXML private Spinner<Integer> extentBufferDistanceSpinner;
  @FXML private Spinner<Integer> minHydrantFlowRateSpinner;
  @FXML private CheckBox systemValvesCheckBox;
  @FXML private CheckBox serviceConnectionsCheckBox;
  @FXML private CheckBox waterPipesCheckBox;
  @FXML private Button generateOfflineMapButton;
  @FXML private ProgressBar progressBar;

  private ArcGISMap map;
  private GraphicsOverlay graphicsOverlay;
  private Graphic downloadArea;

  @FXML
  private void initialize() {
    // handle authentication with the portal
    AuthenticationManager.setAuthenticationChallengeHandler(new DefaultAuthenticationChallengeHandler());

    // create a portal item with the itemId of the web map
    Portal portal = new Portal("https://www.arcgis.com", true);
    PortalItem portalItem = new PortalItem(portal, "acc027394bc84c2fb04d1ed317aac674");

    // create a map with the portal item
    map = new ArcGISMap(portalItem);
    map.addDoneLoadingListener(() -> {
      // enable the generate offline map button when the map is loaded
      if (map.getLoadStatus() == LoadStatus.LOADED) {
        generateOfflineMapButton.setDisable(false);
      }
    });

    // set the map to the map view
    mapView.setMap(map);

    // create a graphics overlay for display the download area
    graphicsOverlay = new GraphicsOverlay();
    mapView.getGraphicsOverlays().add(graphicsOverlay);

    // show a red border around the download area
    downloadArea = new Graphic();
    graphicsOverlay.getGraphics().add(downloadArea);
    SimpleLineSymbol simpleLineSymbol = new SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, 0xFFFF0000, 2);
    downloadArea.setSymbol(simpleLineSymbol);

    // update the download area whenever the viewpoint changes
    mapView.addViewpointChangedListener(viewpointChangedEvent -> {
      if (map.getLoadStatus() == LoadStatus.LOADED) {
        // upper left corner of the area to take offline
        Point2D minScreenPoint = new Point2D(50, 50);
        // lower right corner of the downloaded area
        Point2D maxScreenPoint = new Point2D(mapView.getWidth() - 50, mapView.getHeight() - 50);
        // convert screen points to map points
        Point minPoint = mapView.screenToLocation(minScreenPoint);
        Point maxPoint = mapView.screenToLocation(maxScreenPoint);
        // use the points to define and return an envelope
        if (minPoint != null && maxPoint != null) {
          Envelope envelope = new Envelope(minPoint, maxPoint);
          downloadArea.setGeometry(envelope);
        }
      }
    });
  }

  /**
   * Called when the Generate offline map button is clicked. Builds parameters for the offline map task from the UI
   * inputs and executes the task.
   */
  @FXML
  private void generateOfflineMap() {
    try {
      // show the progress bar
      progressBar.setVisible(true);

      // create an offline map task with the map
      OfflineMapTask offlineMapTask = new OfflineMapTask(map);

      // get default offline map parameters for this task given the download area
      ListenableFuture<GenerateOfflineMapParameters> generateOfflineMapParametersFuture = offlineMapTask
          .createDefaultGenerateOfflineMapParametersAsync(downloadArea.getGeometry());

      generateOfflineMapParametersFuture.addDoneListener(() -> {
        try {
          final GenerateOfflineMapParameters parameters = generateOfflineMapParametersFuture.get();

          // get additional offline parameters (overrides) for this task
          ListenableFuture<GenerateOfflineMapParameterOverrides> parameterOverridesFuture = offlineMapTask
              .createGenerateOfflineMapParameterOverridesAsync(parameters);

          parameterOverridesFuture.addDoneListener(() -> {
            try {
              GenerateOfflineMapParameterOverrides overrides = parameterOverridesFuture.get();

              // get the export tile cache parameters for the base layer
              OfflineMapParametersKey basemapParamKey = new OfflineMapParametersKey(mapView.getMap().getBasemap().getBaseLayers().get(0));
              ExportTileCacheParameters exportTileCacheParameters =
                  overrides.getExportTileCacheParameters().get(basemapParamKey);

              // create a new sublist of LODs in the range requested by the user
              exportTileCacheParameters.getLevelIDs().clear();
              for (int i = minScaleLevelSpinner.getValue(); i < maxScaleLevelSpinner.getValue(); i++) {
                exportTileCacheParameters.getLevelIDs().add(i);
              }
              // set the area of interest to the original download area plus a buffer
              exportTileCacheParameters.setAreaOfInterest(GeometryEngine.buffer(downloadArea.getGeometry(),
                  extentBufferDistanceSpinner.getValue()));

              // configure layer option parameters for each layer depending on the options selected in the UI
              for (Layer layer : map.getOperationalLayers()) {
                if (layer instanceof FeatureLayer) {
                  FeatureLayer featureLayer = (FeatureLayer) layer;
                  ServiceFeatureTable featureTable = (ServiceFeatureTable) featureLayer.getFeatureTable();
                  long layerId = featureTable.getLayerInfo().getServiceLayerId();
                  // get the layer option parameters specifically for this layer
                  OfflineMapParametersKey key = new OfflineMapParametersKey(layer);
                  GenerateGeodatabaseParameters generateGeodatabaseParameters =
                      overrides.getGenerateGeodatabaseParameters().get(key);
                  List<GenerateLayerOption> layerOptions = generateGeodatabaseParameters.getLayerOptions();
                  switch (layer.getName()) {
                    // remove the System Valve layer from the layer options if it should not be included
                    case "System Valve":
                      if (!systemValvesCheckBox.isSelected()) {
                        System.out.println("Removing system valves");
                        for (GenerateLayerOption layerOption : layerOptions) {
                          if (layerOption.getLayerId() == layerId) {
                            layerOptions.remove(layerOption);
                            break;
                          }
                        }

                      }
                      break;
                    case "Service Connection":
                      // remove the Service Connection layer from the layer options if it should not be included
                      if (!serviceConnectionsCheckBox.isSelected()) {
                        System.out.println("Removing service connection");
                        for (GenerateLayerOption layerOption : layerOptions) {
                          if (layerOption.getLayerId() == layerId) {
                            layerOptions.remove(layerOption);
                            break;
                          }
                        }
                      }
                      break;
                    case "Main":
                      //clip water main feature geometries to the extent if the checkbox is selected
                      if (waterPipesCheckBox.isSelected()) {
                        for (GenerateLayerOption layerOption : layerOptions) {
                          if (layerOption.getLayerId() == layerId) {
                            layerOption.setUseGeometry(true);
                          }
                        }
                      }
                      break;
                    case "Hydrant":
                      // only download hydrant features if their flow is above the minimum specified in the UI
                      for (GenerateLayerOption layerOption : layerOptions) {
                        if (layerOption.getLayerId() == layerId) {
                          layerOption.setWhereClause("FLOW >= " + minHydrantFlowRateSpinner.getValue());
                          layerOption.setQueryOption(GenerateLayerOption.QueryOption.USE_FILTER);
                        }
                      }
                  }
                }
              }

              // create an offline map job with the download directory path and parameters and start the job
              Path tempDirectory = Files.createTempDirectory("offline_map");
              GenerateOfflineMapJob job = offlineMapTask.generateOfflineMap(parameters,
                  tempDirectory.toAbsolutePath().toString(), overrides);
              job.start();
              job.addJobDoneListener(() -> {
                if (job.getStatus() == Job.Status.SUCCEEDED) {
                  // replace the current map with the result offline map when the job finishes
                  GenerateOfflineMapResult result = job.getResult();
                  mapView.setMap(result.getOfflineMap());
                  graphicsOverlay.getGraphics().clear();
                  generateOfflineMapButton.setDisable(true);
                } else {
                  new Alert(Alert.AlertType.ERROR, job.getError().getAdditionalMessage()).show();
                }
                Platform.runLater(() -> progressBar.setVisible(false));
              });
              // show the job's progress with the progress bar
              job.addProgressChangedListener(() -> progressBar.setProgress(job.getProgress() / 100.0));
            } catch (ExecutionException | InterruptedException | IOException ex) {
              ex.printStackTrace();
            }
          });
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      });
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  /**
   * Stops the animation and disposes of application resources.
   */
  void terminate() {

    if (mapView != null) {
      mapView.dispose();
    }
  }

}
