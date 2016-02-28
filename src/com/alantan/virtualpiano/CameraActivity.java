package com.alantan.virtualpiano;

import android.support.v7.app.ActionBarActivity;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import android.annotation.SuppressLint;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.Window;
import android.view.WindowManager;

@SuppressLint("NewApi")
//Use the deprecated Camera class.
@SuppressWarnings("deprecation")
public class CameraActivity extends ActionBarActivity implements CvCameraViewListener2 {
	// A tag for log output.
	private static final String TAG = CameraActivity.class.getSimpleName();
	
	// A key for storing the index of the active camera.
	private static final String STATE_CAMERA_INDEX = "cameraIndex";
	
	// A key for storing the index of the active image size.
	private static final String STATE_IMAGE_SIZE_INDEX = "imageSizeIndex";
	
	// An ID for items in the image size submenu.
	private static final int MENU_GROUP_ID_SIZE = 2;
	
	// The index of the active camera.
	private int mCameraIndex;
	
	// The index of the active image size.
	private int mImageSizeIndex;
	
	// Whether the active camera is front-facing.
	// If so, the camera view should be mirrored.
	private boolean mIsCameraFrontFacing;
	
	// The number of cameras on the device.
	private int mNumCameras;
	
	// The camera view.
	private CameraBridgeViewBase mCameraView;
	
	// The image sizes supported by the active camera.
	private List<Size> mSupportedImageSizes;
	
	// A matrix that is used when saving photos
	private Mat mBgr;
	
	// Whether an asynchronous menu action is in progress.
	// If so, menu interaction should be disabled.
	private boolean mIsMenuLocked;
	
	// PianoDetector
	private PianoDetector mPianoDetector;
	
	// Piano keys contour list
	private List<MatOfPoint> mWhiteKeysLMOP = new ArrayList<MatOfPoint>();
	private List<MatOfPoint> mBlackKeysLMOP = new ArrayList<MatOfPoint>();
	
	// FingerDetector
	private HandDetector mHandDetector;
	
	// Whether piano detection should be applied
	private boolean mIsPianoDetection;
	
	// Whether skin detection should be applied
	private boolean mIsFingersDetection;
	
	// KeyPressDetector
	private KeyPressDetector mKeyPressDetector;
	
	// Whether dilation should be applied
	//private boolean mIsDilation;
	
	// Whether erosion should be applied
	private boolean mIsErosion;
	
	private SoundPoolPlayer sound;
	
	private boolean mIsPianoLayout1;
	
	private List<Point> mPrevFingerTipsLP = new ArrayList<Point>();
	
	// The OpenCV loader callback.
	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(final int status) {
			switch(status) {
			case LoaderCallbackInterface.SUCCESS:
				Log.d(TAG, "OpenCV loaded successfully");
				mCameraView.enableView();
				mPianoDetector = new PianoDetector();
				mHandDetector = new HandDetector();
				mKeyPressDetector = new KeyPressDetector();
				mIsPianoLayout1 = true;
				break;
			default:
				super.onManagerConnected(status);
				break;
			}
		}
	};

	// Suppress backward incompatibility errors because we provide
	// backward-compatible fallbacks.
	@SuppressLint("NewApi")
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		final Window window = getWindow();
		window.addFlags(
			WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		if(savedInstanceState != null) {
			mCameraIndex = savedInstanceState.getInt(STATE_CAMERA_INDEX, 0);
			mImageSizeIndex = savedInstanceState.getInt(STATE_IMAGE_SIZE_INDEX, 0);
		} else {
			mCameraIndex = 0;
			mImageSizeIndex = 0;
		}
		
		final Camera camera;
		
		// Certain data regarding device's cameras are unavailable on Froyo.
		// To avoid runtime errors, we check Build.VERSION.SDK_INT before using the new APIs.
		// Furthermore, to avoid seeing errors during static analysis (that is, before compilation),
		// we add the @SuppressLin("NewApi") annotation to the declaration of onCreate.
		
		//Also note that every call to Camera.open must be paired with a call to the camera instance's release method
		// in order to make the camera available later.
		// Otherwise, our app and other apps may subsequently encounter a RuntimeException when calling Camera.open.
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			CameraInfo cameraInfo = new CameraInfo();
			Camera.getCameraInfo(mCameraIndex, cameraInfo);
			mIsCameraFrontFacing = (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT);
			mNumCameras = Camera.getNumberOfCameras();
			camera = Camera.open(mCameraIndex);
		} else {	// pre-Gingerbread
			// Assume there is only 1 camera and it is rear-facing.
			mIsCameraFrontFacing = false;
			mNumCameras = 1;
			camera = Camera.open();
		}
		
		final Parameters parameters = camera.getParameters();
		camera.release();
		mSupportedImageSizes = parameters.getSupportedPreviewSizes();
		final Size size = mSupportedImageSizes.get(mImageSizeIndex);
		
		mCameraView = new JavaCameraView(this, mCameraIndex);
		//mCameraView.setMaxFrameSize(size.width, size.height);
		mCameraView.setMaxFrameSize(640, 480);
		mCameraView.setCvCameraViewListener(this);
		
		setContentView(mCameraView);
		
		sound = new SoundPoolPlayer(this);
	}
	
	public void onSaveInstanceState(Bundle savedInstanceState) {
		// Save the current camera index.
		savedInstanceState.putInt(STATE_CAMERA_INDEX, mCameraIndex);
		
		// Save the current image size index.
		savedInstanceState.putInt(STATE_IMAGE_SIZE_INDEX, mImageSizeIndex);
		
		super.onSaveInstanceState(savedInstanceState);
	}
	
	// When we switch to a different camera or image size, it will be most convenient to
	// recreate the activity so that onCreate will run again. On Honeycomb and newer Android versions,
	// a recreate method is available, but for backward compatibility,
	// we should write our own alternative implementation
	@SuppressLint("NewApi")
	@Override
	public void recreate() {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			super.recreate();
		} else {
			finish();
			startActivity(getIntent());
		}
	}
	
	// When the activity goes into the background (onPause) or finishes (onDestroy),
	// the camera view should be disabled.
	// When the activity comes into the foreground (onResume), the OpenCVLoader should attempt
	// to initialize the library. (Remember that the camera view is enabled once the library is successfully initialized.)
	
	@Override
	public void onPause() {
		if(mCameraView != null) {
			mCameraView.disableView();
		}
		super.onPause();
	}
	
	@Override
	public void onResume() {
		super.onResume();
		// Replace OpenCVLoader.OPENCV_VERSION_3_0_0 with an earlier version
		// such as OpenCVLoader.OPENCV_VERSION_2_4_9 to adapt the code to OpenCV 2.x
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
		mIsMenuLocked = false;
	}
	
	@Override
	public void onDestroy() {
		if(mCameraView != null) {
			mCameraView.disableView();
		}
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_camera, menu);
		if(mNumCameras < 2) {
			// Remove the option to switch cameras, since there is only 1.
			menu.removeItem(R.id.menu_next_camera);
		}
		
		int numSupportedImageSizes = mSupportedImageSizes.size();
		if(numSupportedImageSizes > 1) {
			final SubMenu sizeSubMenu = menu.addSubMenu(R.string.menu_image_size);
			for(int i=0; i<numSupportedImageSizes; i++) {
				final Size size = mSupportedImageSizes.get(i);
				sizeSubMenu.add(MENU_GROUP_ID_SIZE, i, Menu.NONE, String.format("%dx%d", size.width, size.height));
			}
		}
		return true;
	}

	// Suppress backward incompatibility errors because we provide
	// backward-compatible fallbacks (for recreate).
	@SuppressLint("NewApi")
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getGroupId() == MENU_GROUP_ID_SIZE) {
			mImageSizeIndex = item.getItemId();
			recreate();
			
			return true;
		}
		
		switch (item.getItemId()) {
		case R.id.menu_erosion:
			mIsErosion = !mIsErosion;
			return true;
		case R.id.menu_detect_piano:
			mIsPianoDetection = !mIsPianoDetection;
			mWhiteKeysLMOP.clear();
			mBlackKeysLMOP.clear();
			return true;
		case R.id.menu_set_piano:
			mIsPianoDetection = false;
			setPianoKeys();
			return true;
		case R.id.menu_detect_skin:
			mIsFingersDetection = !mIsFingersDetection;
			return true;
		case R.id.menu_change_layout:
			mIsPianoLayout1 = !mIsPianoLayout1;
			return true;
		case R.id.menu_next_camera:
			mIsMenuLocked = true;
			
			// With another camera index, recreate the activity.
			mCameraIndex = (mCameraIndex + 1) % mNumCameras;
			recreate();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onCameraViewStarted(int width, int height) {
		Log.i(TAG, "onCameraViewStarted");
		Log.i(TAG, "Width: " + width + " Height: " + height);
	}

	@Override
	public void onCameraViewStopped() {
		Log.i(TAG, "onCameraViewStopped");
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		final Mat rgba = inputFrame.rgba();
		
		if(mIsPianoDetection) {
			mPianoDetector.apply(rgba, rgba);
		}
		
		if(mIsFingersDetection) {
			mHandDetector.apply(rgba, rgba);
			if(!mHandDetector.getFingerTipsLPOut().isEmpty() && !mWhiteKeysLMOP.isEmpty() && !mBlackKeysLMOP.isEmpty()) {
				checkKeyPressed(rgba);
			}
		}
		
		if(!mWhiteKeysLMOP.isEmpty()) {
			mPianoDetector.drawAllContours(rgba, mWhiteKeysLMOP, Colors.mLineColorGreen, 1);
		}
		
		if(!mBlackKeysLMOP.isEmpty()) {
			mPianoDetector.drawAllContours(rgba, mBlackKeysLMOP, Colors.mLineColorYellow, 1);
		}
		
		if(mIsErosion) {
			Imgproc.cvtColor(rgba, rgba, Imgproc.COLOR_RGB2HSV);
			Scalar lowerThreshold = new Scalar(3, 50, 120);
			Scalar upperThreshold = new Scalar(33, 255, 255);
			Core.inRange(rgba, lowerThreshold, upperThreshold, rgba);
			
		}
		
		// Flip image if using front facing camera
		if(mIsCameraFrontFacing) {
			// Mirror (horizontally flip) the previews.
			Core.flip(rgba, rgba, 1);
		}
		
		return rgba;
	}
	
	private void checkKeyPressed(Mat dst) {
		List<Point> mCurrFingerTipsLP = mHandDetector.getFingerTipsLPOut();
		
		for(int m=0; m<mCurrFingerTipsLP.size(); m++) {
			Imgproc.circle(dst, mCurrFingerTipsLP.get(m), 5, Colors.mLineColorRed, -1);
		}
		
		// If prevFingerTipsLP is empty, set it to current finger tips
		if(mPrevFingerTipsLP.size() == 0) {
			mPrevFingerTipsLP.addAll(mCurrFingerTipsLP);
		}
		
		// Since size of prev and curr LP may vary, it is crucial to find the minimum size
		// so that 'for' loop does not face any indexing error
		int min = (mPrevFingerTipsLP.size() < mCurrFingerTipsLP.size()) ? mPrevFingerTipsLP.size() : mCurrFingerTipsLP.size();
		
		// For each finger tip point, check for key press
		for(int i=0; i<min; i++) {
			if(mKeyPressDetector.checkFingerDownwardMotion(mPrevFingerTipsLP.get(i), mCurrFingerTipsLP.get(i))) {
				
				int keyPressedIndex = mKeyPressDetector.getPianoKeyIndex(mCurrFingerTipsLP.get(i));
				
				/*if(mKeyPressDetector.isNotConsecutiveKey(keyPressedIndex) && keyPressedIndex != -1) {
					// Play sound and update mPianoKeyIndex
					playSound(keyPressedIndex);
					mKeyPressDetector.setPianoKeyIndex(keyPressedIndex);
					return;
				}*/
				
				playSound(keyPressedIndex);
			}
		}
		
		// Clear prevFingerTipsLP and update to current finger tips LP
		mPrevFingerTipsLP.clear();
		mPrevFingerTipsLP.addAll(mCurrFingerTipsLP);
		
		//mKeyPressDetector.setPianoKeyIndex(-1);
	}
	
	private void setPianoKeys() {
		mWhiteKeysLMOP = mPianoDetector.getWhiteKeysLMOP();
		mBlackKeysLMOP = mPianoDetector.getBlackKeysLMOP();
		mKeyPressDetector.setWhiteKeysMOP2f(mWhiteKeysLMOP);
		mKeyPressDetector.setBlackKeysMOP2f(mBlackKeysLMOP);
		mKeyPressDetector.setDivideConquerX(Imgproc.boundingRect(mWhiteKeysLMOP.get(4)).tl().x);
		mHandDetector.setPianoMaskMOP(mPianoDetector.getPianoMaskMOP());
	}
	
	private void playSound(int i) {
		if(i == -1) return;
		if(mIsPianoLayout1) {
			//play sound from layout 1
			sound.playLayout1Sound(i);
		} else {
			// play sound from layout 2
			sound.playLayout2Sound(i);
		}
	}
}
