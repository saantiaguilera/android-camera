package com.santiago.camera.camera.controller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.View;

import com.santiago.camera.camera.utils.picture.CameraPictureCallback;
import com.santiago.camera.camera.utils.picture.CameraPictureUtilities;
import com.santiago.camera.camera.utils.surface.CameraSurfaceHolder;
import com.santiago.camera.manager.CameraManager;
import com.santiago.camera.manager.orientation.CameraOrientationManager;
import com.santiago.controllers.BaseEventController;
import com.santiago.event.EventManager;

import java.io.IOException;
import java.util.List;

/**
 * Controller for a really basic camera
 *
 * Created by santiago on 09/03/16.
 */
public abstract class BaseCameraController<T extends View & CameraSurfaceHolder & CameraPictureCallback> extends BaseEventController<T> {

    private SurfaceHolder surfaceHolder;
    private BaseCameraSurfaceCallback surfaceCallback;

    private CameraManager cameraManager;

    private boolean surfaceActive = false;

    private Camera camera;

    public BaseCameraController(Context context) {
        this(context, null);
    }

    public BaseCameraController(Context context, T t) {
        super(context, t);

        cameraManager = new CameraManager(context);
    }

    /*-----------------Class overrides-----------------*/

    @Override
    protected void onViewAttached(T t) {
        surfaceHolder = t.getSurfaceHolder();

        //In case they dont set us an EventHandler, we do it on our own because we will need to broadcast things internally. If they do then dont mind this
        setEventHandlerListener(new EventManager(getContext()));

        //As soon as we are setting him a callback, process will start and we will eventually be notified (in the cameraSurfaceHandler class about its creation)
        surfaceHolder.addCallback(surfaceCallback = new BaseCameraSurfaceCallback());
    }

    /*----------------------Getters & Setters-------------------------*/

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    /**
     * If the camera is showing or not
     * @return
     */
    public boolean isCameraActive() { return surfaceActive; }

    /**
     * If the camera exists sets the surfaceholder in it
     */
    private void setPreviewDisplay() {
        if(camera==null)
            return;

        try {
            //Since the surface is created, set the camera in it
            camera.setPreviewDisplay(surfaceHolder);

            surfaceCallback.refreshSurface();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*---------------------------Methods---------------------------------*/

    /**
     * Take a picture and show it in the view
     */
    public void takePicture() {
        cameraManager.prepareForPicture();

        camera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                //Get the bitmap (dont recycle it since it will delete the byte array and camera still uses it
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

                boolean isFrontCamera = cameraManager.getCameraTypeManager().getCurrentCamera().getCameraType() == Camera.CameraInfo.CAMERA_FACING_FRONT;
                CameraOrientationManager orientationManager = cameraManager.getCameraOrientationManager();

                int rotation = CameraPictureUtilities.getRotation(orientationManager.getDisplayOrientation(), orientationManager.getNormalOrientation(), orientationManager.getLayoutOrientation(), isFrontCamera);

                bitmap = CameraPictureUtilities.rotatePicture(getContext(), rotation, bitmap);

                if (isFrontCamera)
                    bitmap = CameraPictureUtilities.mirrorImage(bitmap);

                //Set the picture
                getView().onPictureTaken(bitmap);
                getView().onPictureVisibilityChanged(View.VISIBLE);

                //Stop the camera since it wont be used while the picture is showing
                stopCamera();

                //Notify
                onPictureGenerated(bitmap);
            }
        });
    }

    /**
     * Call for starting the camera
     */
    public void startCamera() {
        //Hide the picture if its visible
        getView().onPictureVisibilityChanged(View.GONE);

        //If the camera is already running, stop it
        if(camera!=null)
            stopCamera();

        camera = cameraManager.createNewCamera();

        //Set the preview display again
        setPreviewDisplay();

        camera.startPreview();
    }

    /**
     * Call for stopping the camera
     * <strong> When taking a picture and showing it its higly recommended to stop the camera to free memory </strong>
     * <strong> Always call this when you finished using the camera to make it available to others </strong>
     */
    public void stopCamera() {
        if(camera==null)
            return;

        if (surfaceActive)
            camera.stopPreview();

        camera.release();
        camera = null;

        surfaceActive = false;
    }

    /*-------------------------Abstracty methods---------------------------*/

    protected abstract void onPictureGenerated(Bitmap bitmap);

    /*----------------------Surface Callback Class------------------------*/

    private class BaseCameraSurfaceCallback implements SurfaceHolder.Callback {

        private static final int NO_VALUE = -1;

        private int width = NO_VALUE;
        private int height = NO_VALUE;

        public BaseCameraSurfaceCallback() {
        }

        /**
         * Called when the surface has being created
         * @param holder
         */
        public void surfaceCreated(SurfaceHolder holder) {
            //Start the camera when the surface is created
            startCamera();
        }

        /**
         * Called when the surface suffers some change (rotation, picture taken, etc)
         * @param holder
         * @param format
         * @param width
         * @param height
         */
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if(camera==null)
                return;

            //Refresh our internal values to keep track of them in case we refresh the surface (when the camera suffers changes)
            this.width = width;
            this.height = height;

            refreshSurface();
        }

        public void surfaceDestroyed(SurfaceHolder holder) { }

        protected void refreshSurface() {
            //If we dont track of the width/height of our surface, we cant do this operation
            if(width==NO_VALUE && height==NO_VALUE)
                return;

            //Data will be using
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size previewSize;
            Camera.Size pictureSize;

            //Get the best size for this surface and if exists, set it and calculate the one for the picture (with the ratio setted for the preview)
            previewSize = getBestPictureSize(width, height, parameters.getSupportedPreviewSizes());
            if(previewSize!=null) {
                parameters.setPreviewSize(previewSize.width, previewSize.height);

                //Get the best picture size for this surface, in relation with the setted preview size and set it
                pictureSize = getBestPictureSize(previewSize.width, previewSize.height, parameters.getSupportedPictureSizes());
                if (pictureSize != null)
                    parameters.setPictureSize(pictureSize.width, pictureSize.height);
            }

            //Update data
            camera.setParameters(parameters);
            camera.startPreview();

            surfaceActive = true;
        }

        /**
         * Get the best size resembling the provided aspect ratio from the list of sizes
         *
         * @note <strong>width its the height and height its the width, since physical camera
         * is installed in landscape mode (this means, x is y and y is x.</strong>
         *
         * @param width
         * @param height
         * @param sizes
         * @return Best Size that fits the given parameters
         */
        private Camera.Size getBestPictureSize(int width, int height, List<Camera.Size> sizes) {
            if (sizes == null)
                return null;

            //Get the ratio we want to achieve (read as width / height)
            double targetRatio= (double) height / width;

            //Init data
            Camera.Size optimalSize = null;
            double minRatioTolerance = Double.MAX_VALUE;
            //double minHeightTolerance = (double) height;

            /**
             * Iterate through all our available sizes, get the ratio of each and compare them  with our ratio.
             * If its lower than our minimal difference achieved by now (initially its the highest value possible, so first value will enter yes or yes)
             * change the current optimal one and update the minimal difference.
             *
             * @note Im forcing size.width (the height) to be higher than the surface height, this way we prevent the picture to be of 144px or those really little
             * sizes. On the other hand we could also try to get the max height possible that also respects the lower ratio, but problem is that if the first size is
             * eg REALLY high and with a shit ratio, all the others wont enter since our heightTolerance is damn high in comparison to others. so I just opted for the first way
             */
            for (Camera.Size size : sizes) {
                double ratioSpectre = Math.abs(((double) size.width / size.height) - targetRatio);

                if (ratioSpectre < minRatioTolerance && size.height > width) { //size.width > minHeightTolerance
                    optimalSize = size;
                    minRatioTolerance = ratioSpectre;
                    //minHeightTolerance = size.width;
                }
            }

            return optimalSize;
        }

    }

}