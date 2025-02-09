package com.vidici.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;

import com.netcompss.ffmpeg4android.GeneralUtils;
import com.netcompss.loader.LoadJNI;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class CapturedVideoActivity extends Activity { // Test phone screen off/lock
    private final String TAG = "Log." + this.getClass().getSimpleName();
    private VideoView vidView;
    private Button uploadButton;
    private Context myContext;
    private ProgressBar spinner;
    String videoId;
    String uploadPath;
    String caption;
    String userId;
    String channelId;
    PowerManager.WakeLock wakeLock;
    private String originalPath;
    private Timer timer;
    private Activity myActivity;
    Boolean cameraFront;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myContext = this;
        myActivity = this;
        setContentView(R.layout.activity_captured_video);
        Logger.log(Log.INFO, TAG, "Creating Activity");
        vidView = (VideoView) findViewById(R.id.capturedVideo);
        spinner = (ProgressBar)findViewById(R.id.uploading_in_progress);
        spinner.setVisibility(View.GONE);
        uploadPath = Video.uploadDir.getAbsolutePath();
        videoId = getIntent().getExtras().getString("videoId");
        originalPath = getIntent().getExtras().getString("originalPath");
        channelId = getIntent().getExtras().getString("channelId");
        cameraFront = getIntent().getExtras().getBoolean("cameraFront");
        final Uri vidUri = Uri.parse(uploadPath + "/" + videoId);
        vidView.setVideoURI(vidUri);
	    vidView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
		    @Override
		    public void onPrepared(MediaPlayer mp) {
			    mp.setLooping(true);
		    }
	    });
        uploadButton = (Button) findViewById(R.id.button_upload);
        uploadButton.setOnClickListener(uploadListener);
        if (User.admin) {
            createThumbnailGalleryFile();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logger.log(Log.INFO, TAG, "Resuming Activity");
        Alert.showVideoRule(this);
        vidView.start();
        Logger.fbActivate(this, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.log(Log.INFO, TAG, "Pausing Activity");
        Logger.fbActivate(this, false);
    }

//    class StopPlaybackTask extends TimerTask { // when files selected from gallery are too long
//        public void run() {
//            // When you need to modify a UI element, do so on the UI thread.
//            if (vidView.isPlaying() && originalPath != null) {
//                if (vidView.getCurrentPosition() > 10000) {
//                    vidView.stopPlayback();
//                }
//            }
//            timer.cancel(); //Terminate the timer thread
//        }
//    }

    View.OnClickListener uploadListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!User.loggedIn(myContext)) {
                return;
            }
            uploadButton.setEnabled(false);
            EditText captionText = (EditText) findViewById(R.id.editText_caption);
            caption = captionText.getText().toString().trim();
            if (caption.length() == 0) {
                Toast.makeText(myContext, "Please add a caption first", Toast.LENGTH_LONG).show();
                uploadButton.setEnabled(true);
            } else {
                PowerManager mgr = (PowerManager)myContext.getSystemService(Context.POWER_SERVICE);
                wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UploadingWakeLock");
                wakeLock.acquire(300000); // time out in case something goes wrong
                Toast toast = Toast.makeText(myContext, "Uploading your new scene...", Toast.LENGTH_LONG);
                Logger.trackEvent(myActivity, "Upload", "Upload Video");
                toast.show();
                vidView.pause();
                spinner.setVisibility(View.VISIBLE);
                uploadButton.setVisibility(View.GONE);
                captionText.setKeyListener(null);
                captionText.setEnabled(false);
                UploadVideoTask upload = new UploadVideoTask();
                upload.execute();
                finish();
            }
        }
    };


    class UploadVideoTask extends AsyncTask<Void, Void, JSONObject> {

        protected JSONObject doInBackground(Void... params) {
            String college_admin = "";
            if (!User.admin) {
                compressVideo();
            } else {
                college_admin = myActivity.getSharedPreferences("com.vidici.android", Context.MODE_PRIVATE).getString("college", "");
            }
            userId = User.getId(myContext);
            AppEngine gae = new AppEngine();
            Logger.log(Log.INFO, TAG, String.format("Uploading uploadPath %s videoId %s caption %s userId %s",
                    uploadPath, videoId, caption, userId));
            JSONObject response = gae.uploadVideo(uploadPath, videoId, caption, userId, channelId, college_admin);
            return response;
        }

        protected void onPostExecute(JSONObject response) {
            try {
                if (response != null) {
                    if (response.getString("success").equals("1")) {
                        Toast.makeText(myContext, "Scene uploaded", Toast.LENGTH_LONG).show();
                        spinner.setVisibility(View.GONE);
                    } else {
                        Toast.makeText(myContext, "Failed to upload", Toast.LENGTH_LONG).show();
                        spinner.setVisibility(View.GONE);
                        Logger.log(new Exception("Server Side failure"));
                    }
                } else {
                    Toast.makeText(myContext, "Please check your connectivity and try again later!", Toast.LENGTH_LONG).show();
                }
            } catch (JSONException e) {
                Logger.log(e);;
            }
            new File(uploadPath + "/"+ videoId).delete();
            File[] listFile = Video.uploadDir.listFiles();
            if (listFile != null) {
                for (int i = 0; i < listFile.length; i++) {
                    if (listFile[i].getAbsolutePath().endsWith(".mp4")) {
                        continue; // can we safely delete all files if other compression going on?
                    }
                    listFile[i].delete();
                }
            }
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private void compressVideo() {
        LoadJNI vk = new LoadJNI(); // reduce library size
        try {
            File tmp = new File(uploadPath + "/tmp" + videoId);
            new File(uploadPath + "/" + videoId).renameTo(tmp);
            Logger.log(Log.INFO, TAG, "Pre compression size " + tmp.length());
            // ac audio channels ar audio frequency b bitrate
            String command = "ffmpeg -y -i " + uploadPath + "/tmp"+ videoId +
                    " -strict experimental -s 568x320 -vcodec libx264 -crf 23 -t 12 " + uploadPath + "/" + videoId;
            Logger.log(Log.INFO, TAG, command);
            String[] complexCommand = GeneralUtils.utilConvertToComplex(command);
            vk.run(complexCommand, uploadPath, getApplicationContext());
            Logger.log(Log.INFO, TAG, "Post compression size " + new File(uploadPath + "/" + videoId).length());

            String thumbnailCommand = "ffmpeg -y -i " + uploadPath + "/"+ videoId + " -vf scale=178:100 -ss 00:00:00.000 -t 00:00:03 "
                    + uploadPath + "/" + videoId + ".jpg";
            Logger.log(Log.INFO, TAG, thumbnailCommand);
            complexCommand = GeneralUtils.utilConvertToComplex(thumbnailCommand);
            vk.run(complexCommand, uploadPath, getApplicationContext());

            String thumbnailCropCommand = "ffmpeg -y -i " + uploadPath + "/" + videoId + ".jpg" + " -vf crop=100:100 "
                    + uploadPath + "/" + videoId + ".jpg";
            Logger.log(Log.INFO, TAG, thumbnailCropCommand);
            complexCommand = GeneralUtils.utilConvertToComplex(thumbnailCropCommand);
            vk.run(complexCommand, uploadPath, getApplicationContext());

            if (cameraFront) {
                rotateImage(uploadPath + "/" + videoId + ".jpg", 270);
            } else {
                rotateImage(uploadPath + "/" + videoId + ".jpg", 90);
            }
            tmp.delete();
            new File(uploadPath + "/" + videoId + ".jpg").delete();
        } catch (Throwable e) {
            Logger.log(e);
        }
    }

    void createThumbnailGalleryFile() {
        LoadJNI vk = new LoadJNI(); // reduce library size
        try {
            String thumbnailCommand = "ffmpeg -y -i " + uploadPath + "/"+ videoId + " -vf scale=178:100 -ss 00:00:00.000 -t 00:00:03 "
                    + uploadPath + "/" + videoId + ".jpg";
            Logger.log(Log.INFO, TAG, thumbnailCommand);
            String[] complexCommand = GeneralUtils.utilConvertToComplex(thumbnailCommand);
            vk.run(complexCommand, uploadPath, getApplicationContext());

            String thumbnailCropCommand = "ffmpeg -y -i " + uploadPath + "/" + videoId + ".jpg" + " -vf crop=100:100 "
                    + uploadPath + "/" + videoId + ".jpg";
            Logger.log(Log.INFO, TAG, thumbnailCropCommand);
            complexCommand = GeneralUtils.utilConvertToComplex(thumbnailCropCommand);
            vk.run(complexCommand, uploadPath, getApplicationContext());

            if (cameraFront) {
                rotateImage(uploadPath + "/" + videoId + ".jpg", 270);
            } else {
                rotateImage(uploadPath + "/" + videoId + ".jpg", 90);
            }
        } catch (Throwable e) {
            Logger.log(e);
        }
    }

    private void rotateImage(String path, float degrees) {
        try{
            Bitmap myImg = BitmapFactory.decodeFile(path);
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(myImg, 0, 0, myImg.getWidth(), myImg.getHeight(),
                    matrix, true);
            File file = new File(path.replace(".mp4", ""));
            FileOutputStream fOut = new FileOutputStream(file);
            rotated.compress(Bitmap.CompressFormat.JPEG, 100, fOut);
            fOut.flush();
            fOut.close();}
        catch (Exception e) {
            Logger.log(e);
        }
    }




}