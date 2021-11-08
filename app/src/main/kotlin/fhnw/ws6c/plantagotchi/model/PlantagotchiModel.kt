package fhnw.ws6c.plantagotchi.model

import Base
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.beust.klaxon.Klaxon
import com.shopify.promises.Promise
import fhnw.ws6c.R
import fhnw.ws6c.plantagotchi.AppPreferences
import fhnw.ws6c.plantagotchi.data.GeoPosition
import fhnw.ws6c.plantagotchi.data.connectors.ApiConnector
import fhnw.ws6c.plantagotchi.data.connectors.FirebaseConnector
import fhnw.ws6c.plantagotchi.data.connectors.GPSConnector
import fhnw.ws6c.plantagotchi.data.state.GameState
import fhnw.ws6c.plantagotchi.data.sunrisesunset.SunriseSunset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL
import java.time.ZonedDateTime
import kotlin.concurrent.fixedRateTimer


@SuppressLint("UseCompatLoadingForDrawables")
class PlantagotchiModel(val activity: ComponentActivity) : AppCompatActivity(),
    SensorEventListener {

    private val TAG = "PlantaGotchiModel"
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sensorManager: SensorManager =
        activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var brightness: Sensor? = null
    private var accelerometer: Sensor? = null


    var gameState by mutableStateOf(GameState())
    var gpsConnector = GPSConnector(activity)
    var apiConnector = ApiConnector()
    var firebaseConnector = FirebaseConnector(AppPreferences)

    var statsTitle = "PlantaGotchi Stats"


    private var openWeatherAPIKEY = arrayOf(
        "18b075743cb869bcc0fe9f4977f3696e",
        "89256d338d3202fa44b7deac9b0c208a",
        "1abd7b50c169e42776138698accfefc8",
        "a04c01f715bebacc9295dcf9f22acf12",
        "3867fa45c8569be5ce88e0337c5beba5"
    )

    var loader by mutableStateOf<Drawable?>(null)
    var loading by mutableStateOf(true)
    var loaderText by mutableStateOf("Loading Plantagotchi")


    /**
     * Decays for LUX, CO2, WATER, FERTILIZER
     */
    val LUX_DECAY = 100.0f / 86400.0f // 100 percent / 867400 seconds


    var positionData by mutableStateOf("Getting position ...")
    var position by mutableStateOf(GeoPosition())
    var currentWeather by mutableStateOf("Getting current weather ...")
    var nightDay by mutableStateOf("Checking Night or Day ...")
    var dark by mutableStateOf(false)
    var lastCheck by mutableStateOf("Never checked by now. Wait for next tick")
    var sensorLux by mutableStateOf(0.0f)
    var accelerometerData by mutableStateOf("getting xyz")

    var gameLux by mutableStateOf(100.0)

    // Todo: Maybe redesign later
    init {

        Log.d(TAG, "playerId ${AppPreferences.player_id}")

        brightness = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, brightness, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)

        fixedRateTimer(
            name = "plantagotchi-data-loop",
            initialDelay = 0,
            period = 60000,
            daemon = true
        ) {
            dataLoop()
        }

        firebaseConnector
            .loadInitialGameState
            .whenComplete {
                when (it) {
                    is Promise.Result.Success -> {
                        gameState = it.value

                        fixedRateTimer(
                            name = "plantagotchi-game-loop",
                            initialDelay = 0,
                            period = 1000,
                            daemon = true
                        ) {
                            gameLoop()
                        }

                    }
                    is Promise.Result.Error -> it.error.message?.let { it1 -> Log.e(TAG, it1) }
                }
            }


    }


    fun createNewGameStateInFirebase() {
        gameState.playerState.lux = 100.0
        gameState.playerState.love = 100.0
        gameState.playerState.co2 = 100.0
        gameState.playerState.fertilizer = 100.0
        firebaseConnector.createNewGameState(gameState)
    }


    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            sensorLux = event.values[0]
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerData = "${event.values[1]}/${event.values[0]}/${event.values[2]}"
        }
    }

    fun gameLoop() {
        checkLux()
        firebaseConnector.updateGameState(gameState)
    }


    fun checkLux() {
        if (sensorLux > 1000) {
            if (gameState.playerState.lux <= 100.0) {
                gameState.playerState.lux += 0.1
            } else {
                gameState.playerState.lux = 100.0
            }
        } else {
            gameState.playerState.lux -= LUX_DECAY
        }

        Log.d(TAG, "GameLux: $gameLux")
    }


    fun setLoader(percent: Int, message: String, running: Boolean) {
        loading = running
        loaderText = "$message"
        when (percent) {
            0 -> loader = activity.getDrawable(R.drawable.p0)
            10 -> loader = activity.getDrawable(R.drawable.p10)
            20 -> loader = activity.getDrawable(R.drawable.p20)
            30 -> loader = activity.getDrawable(R.drawable.p30)
            40 -> loader = activity.getDrawable(R.drawable.p40)
            50 -> loader = activity.getDrawable(R.drawable.p50)
            60 -> loader = activity.getDrawable(R.drawable.p60)
            70 -> loader = activity.getDrawable(R.drawable.p70)
            80 -> loader = activity.getDrawable(R.drawable.p80)
            90 -> loader = activity.getDrawable(R.drawable.p90)
            100 -> loader = activity.getDrawable(R.drawable.p100)
        }
    }

    fun dataLoop() {
        gpsConnector.getLocation(
            onSuccess = {
                positionData = "${it.latitude},${it.longitude}"
                position = it
                gameState.playerState.lastPosition = it
            },
            onFailure = { positionData = "Cannot get current position" },
            onPermissionDenied = {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                ActivityCompat.requestPermissions(activity, permissions, 10)
            }
        )
        loadWeatherData(position.latitude, position.longitude)
        loadSunriseSunsetData(position.latitude, position.longitude)
    }

    fun getAPIKey(): String {
        return openWeatherAPIKEY[(0 until (openWeatherAPIKEY.size - 1)).random()]
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun loadWeatherData(latitude: Double, longitude: Double) {
        modelScope.launch {
            val urlString =
                "https://api.openweathermap.org/data/2.5/onecall?lat=$latitude&lon=$longitude&exclude=daily,minutely,hourly,alerts&appid=${getAPIKey()}"
            val url = URL(urlString)
            val weatherJSON = apiConnector.getJSONString(url)
            Log.d(TAG, weatherJSON)

            try {
                val weather = Klaxon().parse<Base>(weatherJSON)
                Log.d(TAG, weather.toString())
                if (weather != null) {
                    currentWeather = weather.current.weather[0].description
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in OpenWeatherCall: $e")
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun loadSunriseSunsetData(latitude: Double, longitude: Double) {
        modelScope.launch {
            val url =
                URL("https://api.sunrise-sunset.org/json?lat=$latitude&lng=-$longitude&formatted=0")
            val sunriseSunsetJSON = apiConnector.getJSONString(url)
            Log.d(TAG, sunriseSunsetJSON)

            try {

                val sunriseSunset = Klaxon().parse<SunriseSunset>(sunriseSunsetJSON)
                Log.d(TAG, sunriseSunset.toString())

                if (sunriseSunset != null) {

                    val sunrise = ZonedDateTime.parse(sunriseSunset.results.sunrise)
                    val sunset = ZonedDateTime.parse(sunriseSunset.results.sunset)
                    val currentDateTime = ZonedDateTime.now()

                    lastCheck = currentDateTime.toString()

                    if (currentDateTime > sunrise && currentDateTime < sunset) {
                        nightDay = "We are in daylight"
                        dark = false
                    } else {
                        nightDay = "It's nighttime"
                        dark = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SunriseSunset Call: $e")
            }
        }
    }
}