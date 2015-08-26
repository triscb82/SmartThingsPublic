/**
 *  Accuweather
 *
 *  Date: 2015-02-02
 */

definition(
    name: "AccuWeather",
    namespace: "accuweather",
    author: "danny@megapixelsoftware.com",
    description: "AccuWeather SmartApp",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-SevereWeather.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-SevereWeather@2x.png",
) {
    appSetting "apikey"
}

preferences {
    page(name: "Setup", title: "", content: "authPage", install: true)
}

def getapiurl() {
   return "http://apidev.accuweather.com"
}

def getDeviceName() {
    return "AccuWeather"
}

def getNameSpace() {
    return "accuweather"
}

def getapikey() {
   return appSettings.apikey
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    initialize()
}

def initialize() {
    if (locationIsDefined()) {
        addDevice()
    }
    // runEvery5Minutes("poll")
    runEvery3Hours("poll")
    // run our initial request for data now
    // poll()
    refresh()
    // if phone is set, trigger alerts by default
    if(phone1) {
      childDevice?.sendEvent(name:"aStatus", value: "on")
    }
}

def uninstalled() {
    removeChildDevices(getChildDevices())
}

private removeChildDevices(delete) {
    delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
}

def authPage() {
    return dynamicPage(name: "Setup", title: "", nextPage: null, uninstall: true, install:false) {
        section ("Location") {
            input "zipcode", "text", title: "Zip Code", required: true
        }

        section("Text Alerts (Optional)") {
            input "phone1", "phone", title: "Phone number", required: false
        }

        section() {

            href(name: "hrefNotRequired",
                 title: "AccuWeather Terms of Usage & Privacy Policy",
                 required: false,
                 style: "external",
                 url: "http://m.accuweather.com/en/legal",
                 description: "By installing one or more AccuWeather SmartApps, You accept and agree to AccuWeather’s Terms of Usage and Privacy Policy")
            input(name: "agree", type: "enum", title: "Accept & Agree", description:"", options: ["Agree"], required: true)
        }
    }
}

def locationIsDefined() {
    zipcodeIsValid() || location.zipCode || ( location.latitude && location.longitude )
}

def zipcodeIsValid() {
    zipcode && zipcode.isNumber() && zipcode.size() == 5
}

//CHILD DEVICE METHODS
def addDevice() {
    def dni = app.id + ":" + zipcode
    def d = getChildDevice(dni)
    if(!d) {
        d = addChildDevice(getNameSpace(), getDeviceName(), dni, null, [label:"AccuWeather (${zipcode})", completedSetup: true])

        // Get Location ID
        state.zipcode = zipcodeIsValid() ? zipcode : location.zipcode;
        def params = [
          uri:  getapiurl() + "/locations/v1/search?q=" + state.zipcode + "&apikey=" + getapikey()
        ]
        httpGet(params) { response ->
            log.debug "${response.data}"
            state.pin = response.data[0].Key
        }

        log.trace "created ${d.displayName} with id $dni"
        poll()
    } else {
        log.trace "found ${d.displayName} with id $dni already exists"
    }
}

def padIt(val,size){
    def pad = val.size() - size;
    if (pad > 0) {
      "$val".padLeft(pad, '.')
    }
    return
}

def alertAPI() {
    def devices = getChildDevices()
    devices.each {
        def childDevice = getChildDevice(it.deviceNetworkId)
        if (childDevice) {

            // Alert API
            def params = [
                uri: getapiurl() + "/alerts/v1/" + state.pin + ".json?language=en&details=true&apikey=" + getapikey(),
            ]

            httpGet(params) { response ->

                // Schedule to pull data again after it expires
                def expires = new Date().parse("EEE, dd MMM yyyy hh:mm:ss zzz", response.headers.Expires)
                def expiresString = expires.format("EEEE,\n MMM d, YYYY",location?.timeZone)
                log.debug "alertAPI Expires: $expires"

                runOnce(expires, "alertAPI");
                state.alertExpires = expires;
                
                log.debug response.data

                def alerts = "";
                def links = "";
                response.data.each {
                    alerts += it.Description.English + "\n"
                    links += it.MobileLink + "\n"
                }

                // log.debug "alerts $alerts"
                // log.debug "links $links"

                // if we have alerts active
                // check to see if this alert has changed
                def currentAlert = childDevice?.currentValue("alert")
                // check the alert status
                def aStatus = childDevice?.currentValue("aStatus")

                // log.debug "status: $aStatus for $currentAlert | $alerts"
                if (currentAlert != alerts) {
                    // if the alert has a value, let's send
                    if(alerts != "" && aStatus == "on") {
                    // @todo - test including link
                        if(phone1) {
                          log.debug "send sms to $phone1"
                          sendSms(phone1, "AccuWeather Alert (SmartThings): $alerts \n$links")
                        } else {
                          log.debug "send push to user"
                          sendPush("AccuWeather Alert (SmartThings): $alerts")
                        }
                    }
               }
               childDevice?.sendEvent(name:"alert", value: alerts)
            }
        }
    }
}

def currentconditionsAPI() {
    log.debug "currentconditions api"
    def devices = getChildDevices()
    devices.each {
        def childDevice = getChildDevice(it.deviceNetworkId)
        if (childDevice) {

            def params = [
              uri: getapiurl() + "/currentconditions/v1/" + state.pin + ".json?language=en&details=true&apikey=" + getapikey(),
            ]

            // log.debug params.uri
            httpGet(params) { response ->

                // Schedule to pull data again after it expires
                def expires = new Date().parse("EEE, dd MMM yyyy hh:mm:ss zzz", response.headers.Expires)
                def expiresString = expires.format("EEEE,\n MMM d, YYYY",location?.timeZone)
                log.debug "currentconditionsAPI Expires: $expires"

                runOnce(expires, "currentconditionsAPI");
                state.currentconditionsExpires = expires;

                Date now = new Date()
                def updatedTime = now.format("h:mm a",location?.timeZone)
                def theDate = now.format("EEEE,\n MMM d, YYYY",location?.timeZone)
                childDevice?.sendEvent(name:"theDate", value: "$theDate\nupdated at ${updatedTime}\n↻")

                childDevice?.sendEvent([name:"weather", value: response.data.WeatherText[0]])
                childDevice?.sendEvent([name:"weatherIcon", value: response.data.WeatherIcon[0]])
                childDevice?.sendEvent([name:"temperature", value: response.data.Temperature.Imperial.Value[0]])
                childDevice?.sendEvent([name:"humidity", value: response.data.RelativeHumidity[0]])
                childDevice?.sendEvent([name:"cloudCover", value: response.data.CloudCover[0]])
                childDevice?.sendEvent([name:"realFeel", value: response.data.RealFeelTemperature.Imperial.Value[0]])
                childDevice?.sendEvent([name:"windSpeed", value: response.data.Wind.Speed.Imperial.Value[0]])
                childDevice?.sendEvent([name:"windGusts", value: response.data.WindGust.Speed.Imperial.Value[0]])
                childDevice?.sendEvent([name:"windDirection", value: response.data.Wind.Direction.English[0]])
                childDevice?.sendEvent([name:"uvIndex", value: response.data.UVIndex[0]])

                def summary = "UV Index.............................................${response.data.UVIndex[0]}(of 9)\n"
                summary += "Humidity.................................................${response.data.RelativeHumidity[0]}%\n"
                summary += "Wind Speed...................................${response.data.Wind.Speed.Imperial.Value[0]} MPH\n"
                summary += "Wind Gusts....................................${response.data.WindGust.Speed.Imperial.Value[0]} MPH\n"
                summary += "Wind Direction..........................................${response.data.Wind.Direction.English[0]}\n"

                // sunrise and sunset data (?) - DAILY API

                def rise = childDevice?.currentValue("localSunrise")
                def set = childDevice?.currentValue("localSunset")
                summary += "Sunrise.............................................$rise\n"
                summary += "Sunset..............................................$set \n\n"


                def alert = childDevice?.currentValue("alert")
                if (alert != "") {
                  summary += "\n\n$alert"
                }

                childDevice?.sendEvent(name:"summary", value: summary)
            }
        }
    }
}

def forecastAPI() {
   log.debug "forecast api"
   // Forecast API
    def devices = getChildDevices()
    devices.each {
        def childDevice = getChildDevice(it.deviceNetworkId)
        if (childDevice) {

           def params = [
              uri: getapiurl() + "/forecasts/v1/hourly/12hour/" + state.pin + ".json?language=en&details=true&apikey=" + getapikey(),
           ]

           log.debug params.uri
           httpGet(params) { response ->

                // Schedule to pull data again after it expires
                def expires = new Date().parse("EEE, dd MMM yyyy hh:mm:ss zzz", response.headers.Expires)
                def expiresString = expires.format("EEEE,\n MMM d, YYYY",location?.timeZone)
                log.debug "ForecastAPI Expires: $expires"

                runOnce(expires, "forecastAPI");
                state.forecastExpires = expires;

                // expand to 6, store values as well

                def map = [:]
                for(int i = 0; i < 12; i++) {

                    //summary by hour
                    def dTime = new Date().parse("yyyy-MM-dd'T'HH:mm:ss", response.data[i].DateTime)
                    def hourTime = dTime.format("        \nEEEE\nh:mm a\n      ") //,location?.timeZone
                    def temp = response.data[i].Temperature.Value
                    def real = response.data[i].RealFeelTemperature.Value
                    def hourSummary = "${temp}°\nReal Feel® ${real}°"


                    def hourIcon = response.data[i].WeatherIcon
                    def precip = response.data[i].Rain.Value

                    if (i < 3) {
                        childDevice?.sendEvent([name:"summary${i+1}hr", value: hourSummary])
                        childDevice?.sendEvent([name:"time${i+1}hr", value: hourTime])
                        childDevice?.sendEvent([name:"icon${i+1}hr", value: hourIcon])
                        childDevice?.sendEvent([name:"precip${i+1}hr", value: precip])
                    }

                    map["${i+1}hr"] = ["precipitation": precip, "temperature": temp, "realFeel": real]
                }

                def forecast = new groovy.json.JsonBuilder(map).toString()
                log.debug "forecast: $forecast"
                childDevice?.sendEvent([name:"forecast", value: forecast])
            }
        }
    }
}

def sunriseAPI() {

    log.debug "sunrise api"

    def devices = getChildDevices()
    devices.each {
        def childDevice = getChildDevice(it.deviceNetworkId)
        if (childDevice) {

            def riseAndSet = getSunriseAndSunset()
            def rise = riseAndSet.sunrise;
            def set = riseAndSet.sunset;

            def sunRise = rise.format("h:mma",location?.timeZone)
            def sunSet = set.format("h:mma",location?.timeZone)

            log.debug "Rise and Set: $riseAndSet"
            childDevice?.sendEvent([name:"localSunrise", value: sunRise])
            childDevice?.sendEvent([name:"localSunset", value: sunSet])

            // childDevice?.sendEvent([name:"localSunrise", value: "6:55am"])
            // childDevice?.sendEvent([name:"localSunset", value: "7:30pm"])

            // @todo - use smartThings Sunrise and Sunset functionality

            // 1 Day Forecast API - for Sunrise and Sunset
            /*
            def params = [
                uri: getapiurl() + "/forecasts/v1/daily/1day/" + state.pin + ".json?language=en&details=true&apikey=" + getapikey(),
            ]

            log.debug params.uri
            httpGet(params) { response ->
                def rise = new Date().parse("yyyy-MM-dd'T'HH:mm:ss", response.data.DailyForecasts[0].Sun.Rise);
                def set = new Date().parse("yyyy-MM-dd'T'HH:mm:ss", response.data.DailyForecasts[0].Sun.Set)

                def sunRise = rise.format("h:mma",location?.timeZone)
                def sunSet = set.format("h:mma",location?.timeZone)

                log.debug "rise $sunRise, set $sunSet"


                childDevice?.sendEvent([name:"localSunrise", value: sunRise,
                name:"localSunset", value: sunSet,
                ])
            }
            */
            // @todo - get sunrise and sunset authorized in the api
            // childDevice?.sendEvent([name:"localSunrise", value: "6:55am"])
            // childDevice?.sendEvent([name:"localSunset", value: "7:30pm"])
        }
    }
}

// poll set for every 3 hours, in case one of the scheduled requests fails
def poll() {
    log.debug "run the poll function"
    // do these checks in case the scheduled call at expiration fails for some reason
    
    def now = new Date().format("yyyy-MM-dd'T'HH:mm:ss")
    log.debug "check format ${state.alertExpires} $now"
    
    if(aExp < now) {
        log.debug "confirmed string date comparison"
        alertAPI()
    }

    if(state.currentConditionsExpires < now) {
        currentconditionsAPI()
    }

    if(state.forecastExpires < now) {
      forecastAPI()
    }
    sunriseAPI()
}

// call all of the APIs again
def refresh() {
    log.debug "refresh called"
    sunriseAPI()
    currentconditionsAPI()
    forecastAPI()
    alertAPI()
}