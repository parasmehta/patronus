package com.vdps.ageprivacymobile;

import java.io.*;
import java.util.ArrayList;

import android.app.*;
import android.content.*;
import android.media.ExifInterface;
import android.net.*;
import android.os.*;
import android.view.*;
import android.graphics.*;
import android.widget.*;
import android.provider.*;
import com.microsoft.projectoxford.face.*;
import com.microsoft.projectoxford.face.contract.*;

import static java.lang.Math.min;

public class MainActivity extends Activity {
    // Replace `<API endpoint>` with the Azure region associated with
    // your subscription key. For example,
    // apiEndpoint = "https://westcentralus.api.cognitive.microsoft.com/face/v1.0"
    private final String apiEndpoint = BuildConfig.Endpoint;
    private final int RESOLUTION_SCALE_LIMIT = 1200;
    private final int ADULT_AGE = 18;

    // Replace `<Subscription Key>` with your subscription key.
    // For example, subscriptionKey = "0123456789abcdef0123456789ABCDEF"
    private final String subscriptionKey = BuildConfig.Key;

    private final FaceServiceClient faceServiceClient =
            new FaceServiceRestClient(apiEndpoint, subscriptionKey);

    private final int PICK_IMAGE = 1;
    private ProgressDialog detectionProgressDialog;

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

        detectionProgressDialog = new ProgressDialog(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK &&
                data != null && data.getData() != null) {
            Uri uri = data.getData();
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
            }
            catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            try {
                ExifInterface exif = null;
                try {
                    if (in != null)
                    exif = new ExifInterface(in);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED);
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                        getContentResolver(), uri);
                // Rotate the image based on the exif image tags
                bitmap = rotateBitmap(bitmap, orientation);
                // Scale down image if necessary
                if (bitmap.getWidth() > RESOLUTION_SCALE_LIMIT || bitmap.getHeight() > 1200) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, (int) bitmap.getWidth() / 2,
                            (int) bitmap.getHeight() / 2, true);
                }
                ImageView imageView = findViewById(R.id.imageView1);
                imageView.setImageBitmap(bitmap);
                detectAndFrame(bitmap);

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
                                     new FaceServiceClient.FaceAttributeType[] {
                                        FaceServiceClient.FaceAttributeType.Age,
                                        FaceServiceClient.FaceAttributeType.Gender,
                                        FaceServiceClient.FaceAttributeType.Emotion
                                     }
                            );
                            if (result == null){
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

                        if(!exceptionMessage.equals("")){
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

                        imageView.setImageBitmap(
                                blurFacesAndAddMoodOnBitmap(imageBitmap, underageFaces.toArray(new Face[underageFaces.size()])));
//                        For also drawing the bounded rectangles
//                        imageView.setImageBitmap(
//                                blurFacesAndAddMoodOnBitmap(bitmapWithRectangles, underageFaces.toArray(new Face[underageFaces.size()])));
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
                    }})
                .create().show();
    }

    /**
     * Add emoticon based on emotion detection and bounded blur.
     * @param originalBitmap The original bitmap to draw on
     * @param faces The detected face objects
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
                String emoticon = getEmoticonFromMood(paint, face);
                canvas.drawCircle(
                        faceRectangle.left + faceRectangle.width/2f ,
                        faceRectangle.top + faceRectangle.height/2f,
                        min(min(faceRectangle.width,faceRectangle.height),200),
                        paint);
                textPaint.setTextSize(Math.min(faceRectangle.width, 199));
                canvas.drawText(emoticon, faceRectangle.left + faceRectangle.width/8f,
                        faceRectangle.top + faceRectangle.height*2/3f, textPaint);
            }
        }
        return bitmap;
    }

    /**
     * Get the proper emoticon based on prevalent mood.
     * @param paint The paint object to match color of emoji
     * @param face The face for which we search the emoji
     * @return The emoji for the face at hand.
     */
    private static String getEmoticonFromMood(Paint paint, Face face) {
        String emoticon = "";
        double max_value = 0;
        if (face.faceAttributes.emotion.anger > max_value) {
            max_value = face.faceAttributes.emotion.anger;
            emoticon = "😡";
            paint.setARGB(245, 225, 106, 47);
        }
        else if (face.faceAttributes.emotion.contempt > max_value) {
            max_value = face.faceAttributes.emotion.contempt;
            emoticon = "😒";
            paint.setARGB(245, 252, 241, 108);
        }
        else if (face.faceAttributes.emotion.disgust > max_value) {
            max_value = face.faceAttributes.emotion.disgust;
            emoticon = "🤢";
            paint.setARGB(245, 139, 163, 50);
        }
        else if (face.faceAttributes.emotion.fear > max_value) {
            max_value = face.faceAttributes.emotion.fear;
            emoticon = "😰";
            paint.setARGB(245, 252, 241, 108);
        }
        else if (face.faceAttributes.emotion.happiness > max_value) {
            max_value = face.faceAttributes.emotion.happiness;
            emoticon = "😁";
            paint.setARGB(245, 252, 241, 108);
        }
        else if (face.faceAttributes.emotion.sadness > max_value) {
            max_value = face.faceAttributes.emotion.sadness;
            emoticon = "😢";
            paint.setARGB(245, 252, 241, 108);
        }
        else if (face.faceAttributes.emotion.surprise > max_value) {
            max_value = face.faceAttributes.emotion.surprise;
            emoticon = "😮";
            paint.setARGB(245, 252, 241, 108);
        }
        else if (face.faceAttributes.emotion.neutral > max_value) {
            max_value = face.faceAttributes.emotion.neutral;
            emoticon = "🙂";
            paint.setARGB(245, 252, 241, 108);
        }
        else {
            emoticon = "";
        }
        return emoticon;
    }

    /**
     * Draw bounding rectangles of the recognised faces.
     * @param originalBitmap The original bitmap
     * @param faces The recognised faces
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
     * @param bitmap The bitmap to rotate
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
        }
        catch (OutOfMemoryError e) {
            e.printStackTrace();
            return null;
        }
    }
}
