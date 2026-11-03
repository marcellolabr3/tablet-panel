package br.com.paineltablet;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Uma única textura compartilhada pelo céu e pelos ícones, reduzida para o tablet. */
final class WeatherCloudTexture {
    private static Bitmap cloud;

    private WeatherCloudTexture() { }

    static synchronized Bitmap get(Resources resources) {
        if (cloud == null || cloud.isRecycled()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inSampleSize = 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            cloud = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud, options);
        }
        return cloud;
    }
}
