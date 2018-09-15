package com.vdps.ageprivacymobile;

import java.io.*;
import java.util.ArrayList;

import android.app.*;
import android.content.*;
import android.media.ExifInterface;
import android.net.*;
import android.os.*;
import android.support.v4.content.FileProvider;
import android.view.*;
import android.graphics.*;
import android.widget.*;
import android.provider.*;

import com.microsoft.projectoxford.face.*;
import com.microsoft.projectoxford.face.contract.*;

import static java.lang.Math.min;

import android.util.Log;

public class MainActivity extends Activity {
    // Replace `<API endpoint>` with the Azure region associated with
    // your subscription key. For example,
    // apiEndpoint = "https://westcentralus.api.cognitive.microsoft.com/face/v1.0"
    private final String apiEndpoint = BuildConfig.Endpoint;
    private final int RESOLUTION_SCALE_LIMIT = 1200;
    private final int ADULT_AGE = 18;
    private String imageFilter = "downsample";  // choose from ("emoticon", "downsample")

    // Replace `<Subscription Key>` with your subscription key.
    // For example, subscriptionKey = "0123456789abcdef0123456789ABCDEF"
    private final String subscriptionKey = BuildConfig.Key;

    private final FaceServiceClient faceServiceClient =
            new FaceServiceRestClient(apiEndpoint, subscriptionKey);

    private static final int REQUEST_TAKE_PHOTO = 0;
    private final int PICK_IMAGE = 1;
    private ProgressDialog detectionProgressDialog;

    // The URI of photo taken with camera
    private Uri mUriPhotoTaken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button button1 = findViewById(R.id.button1);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(
                        intent, "Select Picture"), PICK_IMAGE);
            }
        });

        Button button2 = findViewById(R.id.button_take_a_photo);
        button2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                takePhoto(v);
            }
        });

        detectionProgressDialog = new ProgressDialog(this);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_TAKE_PHOTO:
            case PICK_IMAGE:
                if (resultCode == RESULT_OK) {

                    Uri uri;

                    if (data != null && data.getData() != null) {
                        uri = data.getData();
                    } else {
                        uri = mUriPhotoTaken;
                    }
                    InputStream in = null;
                    try {
                        in = getContentResolver().openInputStream(uri);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    try {
                        ExifInterface exif = null;

                        try {
                            if (in != null)
                                exif = new ExifInterface(in);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_UNDEFINED);
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                        // Rotate the image based on the exif image tags
                        bitmap = rotateBitmap(bitmap, orientation);
                        // Scale down image if necessary
                        if (bitmap.getWidth() > RESOLUTION_SCALE_LIMIT || bitmap.getHeight() > RESOLUTION_SCALE_LIMIT) {
                            bitmap = Bitmap.createScaledBitmap(bitmap, (int) bitmap.getWidth() / 2,
                                    (int) bitmap.getHeight() / 2, true);

                        }
                    }
                }

        }
    }


    // When the button of "Take a Photo with Camera" is pressed.
    public void takePhoto(View view) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(intent.resolveActivity(getPackageManager()) != null) {
            // Save the photo taken to a temporary file.
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            try {
                File file = File.createTempFile("IMG_", ".jpg", storageDir);
                mUriPhotoTaken = FileProvider.getUriForFile(MainActivity.this, BuildConfig.APPLICATION_ID + ".provider", file);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, mUriPhotoTaken);
                startActivityForResult(intent, REQUEST_TAKE_PHOTO);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Detect faces by uploading a face image.
    // Frame faces after detection.
    private void detectAndFrame(final Bitmap imageBitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        imageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        ByteArrayInputStream inputStream =
                new ByteArrayInputStream(outputStream.toByteArray());

        AsyncTask<InputStream, String, Face[]> detectTask =
                new AsyncTask<InputStream, String, Face[]>() {
                    String exceptionMessage = "";

                    @Override
                    protected Face[] doInBackground(InputStream... params) {
                        try {
                            publishProgress("Detecting...");
                            Face[] result = faceServiceClient.detect(
                                    params[0],
                                    true,         // returnFaceId
                                    true,        // returnFaceLandmarks
                                    new FaceServiceClient.FaceAttributeType[]{
                                            FaceServiceClient.FaceAttributeType.Age,
                                            FaceServiceClient.FaceAttributeType.Gender,
                                            FaceServiceClient.FaceAttributeType.Emotion
                                    }
                            );
                            if (result == null) {
                                publishProgress(
                                        "Detection Finished. Nothing detected");
                                return null;
                            }
                            publishProgress(String.format(
                                    "Detection Finished. %d face(s) detected",
                                    result.length));
                            return result;
                        } catch (Exception e) {
                            exceptionMessage = String.format(
                                    "Detection failed: %s", e.getMessage());
                            return null;
                        }
                    }

                    @Override
                    protected void onPreExecute() {
                        //TODO: show progress dialog
                        detectionProgressDialog.show();
                    }

                    @Override
                    protected void onProgressUpdate(String... progress) {
                        //TODO: update progress
                        detectionProgressDialog.setMessage(progress[0]);
                    }

                    @Override
                    protected void onPostExecute(Face[] result) {
                        //TODO: update face frames
                        detectionProgressDialog.dismiss();

                        if (!exceptionMessage.equals("")) {
                            showError(exceptionMessage);
                        }
                        if (result == null) return;

                        ImageView imageView = findViewById(R.id.imageView1);
                        ArrayList<Face> underageFaces = new ArrayList<>();
                        for (Face face : result) {
                            if (face.faceAttributes.age < ADULT_AGE) {
                                underageFaces.add(face);
                            }
                        }


//                        Draw rectangles around identified faces
//                        Bitmap bitmapWithRectangles = drawFaceRectanglesOnBitmap(imageBitmap, result);
//                        imageView.setImageBitmap(
//                                drawFaceRectanglesOnBitmap(imageBitmap, result));
                        if (imageFilter.equals("emoticon")) {
                            imageView.setImageBitmap(
                                    blurFacesAndAddMoodOnBitmap(imageBitmap, underageFaces.toArray(new Face[underageFaces.size()])));
//                        For also drawing the bounded rectangles
//                        imageView.setImageBitmap(
//                                blurFacesAndAddMoodOnBitmap(bitmapWithRectangles, underageFaces.toArray(new Face[underageFaces.size()])));
                        }
                        else if (imageFilter.equals("downsample")) {
                            // Blur with downsampling and upsampling
                            imageView.setImageBitmap(
                                    blurFacesDownUpSample(imageBitmap, underageFaces.toArray(new Face[underageFaces.size()])));
                        }
                        imageBitmap.recycle();
                    }
                };

        detectTask.execute(inputStream);
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(message)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                    }
                })
                .create().show();
    }

    /**
     * Add emoticon based on emotion detection and bounded blur.
     *
     * @param originalBitmap The original bitmap to draw on
     * @param faces          The detected face objects
     * @return The new bitmap
     */
    private static Bitmap blurFacesAndAddMoodOnBitmap(
            Bitmap originalBitmap, Face[] faces) {
        Bitmap bitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setMaskFilter(new BlurMaskFilter(25, BlurMaskFilter.Blur.NORMAL));
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(10);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.LEFT);

        if (faces != null) {
            for (Face face : faces) {

                FaceRectangle faceRectangle = face.faceRectangle;
                // Emotion-based processing
                String emoticon = getEmoticonFromMood(paint, face);
                canvas.drawCircle(
                        faceRectangle.left + faceRectangle.width / 2f,
                        faceRectangle.top + faceRectangle.height / 2f,
                        min(min(faceRectangle.width, faceRectangle.height), 200),
                        paint);
                textPaint.setTextSize(Math.min(faceRectangle.width, 199));
                canvas.drawText(emoticon, faceRectangle.left + faceRectangle.width / 8f,
                        faceRectangle.top + faceRectangle.height * 2 / 3f, textPaint);
            }
        }
        return bitmap;
    }

    /**
     * Blur face for privacy reasons, based on subsequent downsampling and upsampling.
     * @param originalBitmap The original bitmap to draw on
     * @param faces The detected face objects
     * @return Te new bitmap
     */
    private static Bitmap blurFacesDownUpSample(
            Bitmap originalBitmap, Face[] faces) {
        Bitmap bitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
//        Log.d("ADebugTag", "bitmap width: " + Integer.toString(bitmap.getWidth()));

        if (faces != null) {
            for (Face face : faces) {

                FaceRectangle faceRectangle = face.faceRectangle;
                // Create new bitmap translated to (0,0) for each face
                int ri = 0;
                Bitmap resultBitmap = Bitmap.createBitmap(faceRectangle.width, faceRectangle.height, Bitmap.Config.ARGB_8888);
                for (int i = faceRectangle.left; i < faceRectangle.width + faceRectangle.left; i++) {
                    int rj = 0;
                    for (int j = faceRectangle.top; j < faceRectangle.height + faceRectangle.top; j++) {
                        resultBitmap.setPixel(ri, rj, bitmap.getPixel(i, j));
                        rj++;
                    }
                    ri++;
                }
//                Log.d("ADebugTag", "resultBitmap width: " + Integer.toString(resultBitmap.getWidth()));
                // Downsample
                Bitmap downBitmap = Bitmap.createScaledBitmap(resultBitmap, (int) (resultBitmap.getWidth() * 0.03),
                        (int) (resultBitmap.getHeight() * 0.03), true);
                // Upsample
                Bitmap upBitmap = Bitmap.createScaledBitmap(downBitmap,
                        (int) resultBitmap.getWidth(),
                        (int) resultBitmap.getHeight(), true);
//                Log.d("ADebugTag", "downBitmap width: " + Integer.toString(downBitmap.getWidth()));
//                Log.d("ADebugTag", "upBitmap width: " + Integer.toString(upBitmap.getWidth()));
//
                // Translate back to the original bitmap.
                int ui = 0;
                for (int i = faceRectangle.left; i < faceRectangle.width + faceRectangle.left; i++) {
                    int uj = 0;
                    for (int j = faceRectangle.top; j < faceRectangle.height + faceRectangle.top; j++) {
                        bitmap.setPixel(i, j, upBitmap.getPixel(ui, uj));
                        uj++;
                    }
                    ui++;
                }
//                Log.d("ADebugTag", "Bitmap width: " + Integer.toString(bitmap.getWidth()));
            }
        }
        return bitmap;
    }

    /**
     * Get the proper emoticon based on prevalent mood.
     *
     * @param paint The paint object to match color of emoji
     * @param face  The face for which we search the emoji
     * @return The emoji for the face at hand.
     */
    private static String getEmoticonFromMood(Paint paint, Face face) {
        String emoticon = "";
        double max_value = 0;
        if (face.faceAttributes.emotion.anger > max_value) {
            max_value = face.faceAttributes.emotion.anger;
            emoticon = "😡";
            paint.setARGB(245, 225, 106, 47);
        } else if (face.faceAttributes.emotion.contempt > max_value) {
            max_value = face.faceAttributes.emotion.contempt;
            emoticon = "😒";
            paint.setARGB(245, 252, 241, 108);
        } else if (face.faceAttributes.emotion.disgust > max_value) {
            max_value = face.faceAttributes.emotion.disgust;
            emoticon = "🤢";
            paint.setARGB(245, 139, 163, 50);
        } else if (face.faceAttributes.emotion.fear > max_value) {
            max_value = face.faceAttributes.emotion.fear;
            emoticon = "😰";
            paint.setARGB(245, 252, 241, 108);
        } else if (face.faceAttributes.emotion.happiness > max_value) {
            max_value = face.faceAttributes.emotion.happiness;
            emoticon = "😁";
            paint.setARGB(245, 252, 241, 108);
        } else if (face.faceAttributes.emotion.sadness > max_value) {
            max_value = face.faceAttributes.emotion.sadness;
            emoticon = "😢";
            paint.setARGB(245, 252, 241, 108);
        } else if (face.faceAttributes.emotion.surprise > max_value) {
            max_value = face.faceAttributes.emotion.surprise;
            emoticon = "😮";
            paint.setARGB(245, 252, 241, 108);
        } else if (face.faceAttributes.emotion.neutral > max_value) {
            max_value = face.faceAttributes.emotion.neutral;
            emoticon = "🙂";
            paint.setARGB(245, 252, 241, 108);
        } else {
            emoticon = "";
            paint.setARGB(250, 255, 255, 255);
        }
        return emoticon;
    }

    /**
     * Draw bounding rectangles of the recognised faces.
     *
     * @param originalBitmap The original bitmap
     * @param faces          The recognised faces
     * @return The bitmap with the rectangles drawn
     */
    private static Bitmap drawFaceRectanglesOnBitmap(
            Bitmap originalBitmap, Face[] faces) {
        Bitmap bitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.RED);
        paint.setStrokeWidth(10);
        if (faces != null) {
            for (Face face : faces) {
                FaceRectangle faceRectangle = face.faceRectangle;
                canvas.drawRect(
                        faceRectangle.left,
                        faceRectangle.top,
                        faceRectangle.left + faceRectangle.width,
                        faceRectangle.top + faceRectangle.height,
                        paint);
            }
        }
        return bitmap;
    }

    /**
     * Rotates the bitmap based on the exif extracted orientation.
     *
     * @param bitmap      The bitmap to rotate
     * @param orientation The orientation extracted from EXIF tags
     * @return The rotated bitmap
     */
    private static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return bitmap;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return bitmap;
        }
        try {
            Bitmap bmRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return bmRotated;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return null;
        }


    }

    // Save the activity state when it's going to stop.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("ImageUri", mUriPhotoTaken);
    }

    // Recover the saved state when the activity is recreated.
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mUriPhotoTaken = savedInstanceState.getParcelable("ImageUri");
    }
}
