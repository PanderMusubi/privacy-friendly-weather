package org.secuso.privacyfriendlyweather.services;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.JobIntentService;

import org.secuso.privacyfriendlyweather.R;
import org.secuso.privacyfriendlyweather.database.AppDatabase;
import org.secuso.privacyfriendlyweather.database.data.City;
import org.secuso.privacyfriendlyweather.database.data.CityToWatch;
import org.secuso.privacyfriendlyweather.database.data.CurrentWeatherData;
import org.secuso.privacyfriendlyweather.database.data.Forecast;
import org.secuso.privacyfriendlyweather.weather_api.IHttpRequestForCityList;
import org.secuso.privacyfriendlyweather.weather_api.IHttpRequestForForecast;
import org.secuso.privacyfriendlyweather.weather_api.IHttpRequestForOneCallAPI;
import org.secuso.privacyfriendlyweather.weather_api.open_weather_map.OwmHttpRequestForForecast;
import org.secuso.privacyfriendlyweather.weather_api.open_weather_map.OwmHttpRequestForOneCallAPI;
import org.secuso.privacyfriendlyweather.weather_api.open_weather_map.OwmHttpRequestForUpdatingCityList;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * This class provides the functionality to fetch forecast data for a given city as a background
 * task.
 */
public class UpdateDataService extends JobIntentService {

    public static final String UPDATE_CURRENT_WEATHER_ACTION = "org.secuso.privacyfriendlyweather.services.UpdateDataService.UPDATE_CURRENT_WEATHER_ACTION";
    public static final String UPDATE_FORECAST_ACTION = "org.secuso.privacyfriendlyweather.services.UpdateDataService.UPDATE_FORECAST_ACTION";
    public static final String UPDATE_ALL_ACTION = "org.secuso.privacyfriendlyweather.services.UpdateDataService.UPDATE_ALL_ACTION";
    public static final String UPDATE_SINGLE_ACTION = "org.secuso.privacyfriendlyweather.services.UpdateDataService.UPDATE_SINGLE_ACTION";

    public static final String CITY_ID = "cityId";
    public static final String SKIP_UPDATE_INTERVAL = "skipUpdateInterval";

    private AppDatabase dbHelper;
    private SharedPreferences prefManager;

    /**
     * Constructor.
     */
    public UpdateDataService() {
        super();
    }

    /**
     * @see IntentService#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = AppDatabase.getInstance(getApplicationContext());
        prefManager = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    /**
     *
     */
    @Override
    protected void onHandleWork(Intent intent) {
        if (!isOnline()) {
            Handler h = new Handler(getApplicationContext().getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.error_no_internet), Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        if (intent != null) {
            if (UPDATE_ALL_ACTION.equals(intent.getAction())) handleUpdateAll(intent);
            else if (UPDATE_CURRENT_WEATHER_ACTION.equals(intent.getAction()))   //should not be needed anymore due to use of One Call API
                handleUpdateCurrentWeatherAction(intent);
            else if (UPDATE_FORECAST_ACTION.equals(intent.getAction()))
                handleUpdateForecastAction(intent);
            else if (UPDATE_SINGLE_ACTION.equals(intent.getAction())) handleUpdateSingle(intent);
        }
    }


    /**
     * Be careful, with using this. It can cause many calls to the API, because it wants to update everything if the update interval allows it.
     *
     * @param intent contains necessary parameters for the service work
     */
    private void handleUpdateAll(Intent intent) {
        //  handleUpdateCurrentWeatherAction(intent); //TODO: remove, now done via one call api
        List<CityToWatch> cities = dbHelper.cityToWatchDao().getAll();
        for (CityToWatch c : cities) {
            handleUpdateForecastAction(intent, c.getCityId(), c.getLatitude(), c.getLongitude());
        }
    }

    private void handleUpdateSingle(Intent intent) {
        int cityId = intent.getIntExtra("cityId", -1);
        City city = dbHelper.cityDao().getCityById(cityId);
        if (cityId == -1 || city == null) {
            Log.d("city null", cityId + " " + city);
            return;
        }
        handleUpdateForecastAction(intent, cityId, city.getLatitude(), city.getLongitude());
    }

    private void handleUpdateForecastAction(Intent intent, int cityId, float lat, float lon) {
        boolean skipUpdateInterval = intent.getBooleanExtra(SKIP_UPDATE_INTERVAL, false);

        // TODO: 07.0

        long timestamp = 0;
        long systemTime = System.currentTimeMillis() / 1000;
        long updateInterval = 2 * 60 * 60;

        if (!skipUpdateInterval) {
            // check timestamp of the current forecasts
            List<Forecast> forecasts = dbHelper.forecastDao().getForecastsByCityId(cityId);
            if (forecasts.size() > 0) {
                timestamp = forecasts.get(0).getTimestamp();
            }


            updateInterval = Long.parseLong(prefManager.getString("pref_updateInterval", "2")) * 60 * 60;
        }

        // only Update if a certain time has passed
        if (skipUpdateInterval || timestamp + updateInterval - systemTime <= 0) {
            //if forecastChoice = 1 (3h) perform both else only one call API
            int choice = Integer.parseInt(prefManager.getString("forecastChoice", "1"));
            if (choice == 1) {
                IHttpRequestForForecast forecastRequest = new OwmHttpRequestForForecast(getApplicationContext());
                forecastRequest.perform(cityId);
            }
            IHttpRequestForOneCallAPI forecastOneCallRequest = new OwmHttpRequestForOneCallAPI(getApplicationContext());
            forecastOneCallRequest.perform(lat, lon);
        }
    }

    private boolean isOnline() {
        try {
            InetAddress inetAddress = InetAddress.getByName("api.openweathermap.org");
            return !inetAddress.equals("");
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void handleUpdateForecastAction(Intent intent) {
        int cityId = intent.getIntExtra(CITY_ID, -1);
        float lat = 0;
        float lon = 0;
        //get lat lon for cityID
        List<CityToWatch> citiesToWatch = dbHelper.cityToWatchDao().getAll();
        for (int i = 0; i < citiesToWatch.size(); i++) {
            CityToWatch city = citiesToWatch.get(i);
            if (city.getCityId() == cityId) {
                lat = city.getLatitude();
                lon = city.getLongitude();
                break;
            }
        }
        handleUpdateForecastAction(intent, cityId, lat, lon);
    }

    private void handleUpdateCurrentWeatherAction(Intent intent) {
        boolean skipUpdateInterval = intent.getBooleanExtra(SKIP_UPDATE_INTERVAL, false);

        long systemTime = System.currentTimeMillis() / 1000;
        boolean shouldUpdate = false;

        if (!skipUpdateInterval) {
            long updateInterval = Long.parseLong(prefManager.getString("pref_updateInterval", "2")) * 60 * 60;

            List<CityToWatch> citiesToWatch = dbHelper.cityToWatchDao().getAll();
            // check timestamp of the current weather .. if one of them is out of date.. update them all at once
            List<CurrentWeatherData> weather = dbHelper.currentWeatherDao().getAll();

            for (CityToWatch city : citiesToWatch) {
                int cityId = city.getCityId();
                boolean foundId = false;
                for (CurrentWeatherData w : weather) {
                    if (w.getCity_id() == cityId) {

                        foundId = true;

                        long timestamp = w.getTimestamp();
                        if (timestamp + updateInterval - systemTime <= 0) {
                            shouldUpdate = true;
                            break;
                        }
                    }
                }
                if (shouldUpdate || !foundId) {
                    shouldUpdate = true;
                    break;
                }
            }
        }

        if (skipUpdateInterval || shouldUpdate) {
            IHttpRequestForCityList currentWeatherRequest = new OwmHttpRequestForUpdatingCityList(getApplicationContext());
            List<CityToWatch> cityToWatches = dbHelper.cityToWatchDao().getAll();
            currentWeatherRequest.perform(cityToWatches);
        }
    }
}
