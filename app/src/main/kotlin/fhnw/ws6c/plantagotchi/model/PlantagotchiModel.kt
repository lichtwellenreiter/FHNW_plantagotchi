package fhnw.ws6c.plantagotchi.model

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.getSystemService
import com.beust.klaxon.Klaxon
import fhnw.ws6c.plantagotchi.data.connectors.ApiConnector
import fhnw.ws6c.plantagotchi.data.connectors.GPSConnector
import fhnw.ws6c.plantagotchi.data.sunrisesunset.SunriseSunset
import fhnw.ws6c.plantagotchi.data.weather.WeatherBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import kotlin.concurrent.fixedRateTimer


class PlantagotchiModel(val activity: ComponentActivity): SensorEventListener {

    private var TAG = "PlantagotchiModel"
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sensorManager: SensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var brightness: Sensor? = null



    var title = "Hello ws6C"
    var gpsConnector = GPSConnector(activity)
    var apiConnector = ApiConnector()

    var openWeatherAPIKEY = "4a95a98df24aeeb48956f2c2f3db0502"


    var position by mutableStateOf("")
    var currentWeather by mutableStateOf("")
    var nightDay by mutableStateOf("")
    var lastCheck by mutableStateOf("")
    var currentLux by mutableStateOf(0.0f)

    // Todo: Maybe redesign later
    init{
        brightness = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        fixedRateTimer(name = "plantagotchi-data-load", initialDelay = 0, period = 10000, daemon = true){
            getCurrentWeather()
        }
    }


    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // braucht es aktuell nicht
    }

    override fun onSensorChanged(event: SensorEvent) {
        if(event.sensor.type == Sensor.TYPE_LIGHT){
            currentLux = event.values[0]
            Log.i(TAG, event.toString())
            Log.i(TAG, "Current lux: $currentLux")
        }
    }


    fun getCurrentWeather() {
        gpsConnector.getLocation(
            onSuccess = {
                position = "${it.latitude},${it.longitude}"

                modelScope.launch {
                    val url =
                        URL("https://api.openweathermap.org/data/2.5/weather?lat=${it.latitude}&lon=${it.longitude}&appid=${openWeatherAPIKEY}")
                    val weatherJSON = apiConnector.getJSONString(url)
                    Log.i(TAG, weatherJSON)

                    val weather = Klaxon().parse<WeatherBase>(weatherJSON)

                    Log.i(TAG, weather.toString())

                    if (weather != null) {
                        currentWeather = weather.weather[0].main
                    }

                }

                modelScope.launch {
                    val url =
                        URL("https://api.sunrise-sunset.org/json?lat=${it.latitude}&lng=-${it.longitude}&formatted=0\n")
                    val sunriseSunsetJSON = apiConnector.getJSONString(url)
                    Log.i(TAG, sunriseSunsetJSON)

                    val sunriseSunset = Klaxon().parse<SunriseSunset>(sunriseSunsetJSON)
                    Log.i(TAG, sunriseSunset.toString())

                    if (sunriseSunset != null) {

                        val sunrise = ZonedDateTime.parse(sunriseSunset.results.sunrise)
                        val sunset = ZonedDateTime.parse(sunriseSunset.results.sunset)
                        val currentDateTime = ZonedDateTime.now()

                        lastCheck = currentDateTime.toString()

                        if(currentDateTime > sunrise && currentDateTime < sunset){
                            nightDay = "We are in daylight"
                        } else {
                            nightDay = "It's nighttime"
                        }

                    }
                }


            },
            onFailure = { position = "Cannot get current location" },
            onPermissionDenied = {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                ActivityCompat.requestPermissions(activity, permissions, 10)
            }
        )
    }

}