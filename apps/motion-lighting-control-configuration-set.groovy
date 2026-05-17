definition(
    name: "Motion Lighting Control Configuration Set",
    namespace: "ejtbrown",
    parent: "ejtbrown:Motion Lighting Control",
    author: "Eric Brown",
    description: "Controls one set of dimmers and multi-toggle switches from a motion or presence sensor.",
    category: "Convenience",
    iconUrl: "https://raw.githubusercontent.com/ejtbrown/lightingcontrol/main/resources/motion-lighting-control.png",
    iconX2Url: "https://raw.githubusercontent.com/ejtbrown/lightingcontrol/main/resources/motion-lighting-control.png",
    singleInstance: false
)

preferences {
    page(name: "mainPage", title: configurationTitle(), install: true, uninstall: true) {
        section("Configuration set") {
            label title: "Configuration set name",
                required: true
        }

        section("Presence detection") {
            input name: "motionDevice",
                type: "capability.motionSensor",
                title: "Motion sensor",
                multiple: false,
                required: false,
                submitOnChange: true

            input name: "presenceDevice",
                type: "capability.presenceSensor",
                title: "Presence sensor",
                multiple: false,
                required: false,
                submitOnChange: true
        }

        section("Controlled lights") {
            input name: "dimmerDevices",
                type: "capability.switchLevel",
                title: "Dimmers",
                multiple: true,
                required: false,
                submitOnChange: true

            input name: "singleToggleSwitches",
                type: "capability.switch",
                title: "Single-toggle switches",
                multiple: true,
                required: false,
                submitOnChange: true

            input name: "multiToggleSwitches",
                type: "capability.switch",
                title: "Multi-toggle switches",
                multiple: true,
                required: false,
                submitOnChange: true
        }

        if (dimmerDevices) {
            section("Dimmer levels") {
                input name: "dayLevel",
                    type: "number",
                    title: "Daytime dimmer level",
                    description: "1-100",
                    defaultValue: 100,
                    required: true

                input name: "nightLevel",
                    type: "number",
                    title: "Nighttime dimmer level",
                    description: "1-100",
                    defaultValue: 25,
                    required: true
            }
        }

        if (multiToggleSwitches) {
            section("Multi-toggle") {
                input name: "multiToggleDwellMs",
                    type: "number",
                    title: "Multi-toggle dwell time, milliseconds",
                    defaultValue: 500,
                    required: true
            }
        }

        section("Daylight and nighttime") {
            input name: "dayStart",
                type: "enum",
                title: "Daylight starts at",
                options: timeBoundaryOptions(),
                defaultValue: "sunrise",
                required: true

            input name: "nightStart",
                type: "enum",
                title: "Nighttime starts at",
                options: timeBoundaryOptions(),
                defaultValue: "sunset",
                required: true
        }

        section("Off delay") {
            input name: "offDelaySeconds",
                type: "number",
                title: "Seconds to leave lights on after motion or presence stops",
                defaultValue: 300,
                required: true
        }

        section("Logging") {
            input name: "debugLogging",
                type: "bool",
                title: "Enable debug logging",
                defaultValue: false,
                required: false
        }

        if (!configurationComplete()) {
            section("Configuration status") {
                paragraph "Select at least one motion or presence sensor and at least one dimmer, single-toggle switch, or multi-toggle switch."
            }
        }
    }
}

def installed() {
    log.info "Installed ${configurationTitle()}"
    initialize()
}

def updated() {
    log.info "Updated ${configurationTitle()}"
    unsubscribe()
    unschedule()
    state.clear()
    initialize()
}

def initialize() {
    atomicState.multiToggleInProgress = false
    atomicState.ignoreSwitchEventsUntilMs = null
    atomicState.multiToggleCycleActive = false

    if (!configurationComplete()) {
        log.warn "App is not fully configured. Select a motion or presence sensor and at least one controlled light."
        return
    }

    if (motionDevice) {
        subscribe(motionDevice, "motion", "presenceSourceHandler")
    }

    if (presenceDevice) {
        subscribe(presenceDevice, "presence", "presenceSourceHandler")
    }

    controlledDevices().each { device ->
        subscribe(device, "switch", "controlledSwitchHandler")
    }

    if (isPresenceActive()) {
        clearOffDeadline()
    } else {
        scheduleOffDelay()
    }

    ensureTargetState("initialize")
}

def presenceSourceHandler(evt) {
    logDebug "Presence source event: ${evt.device} ${evt.name}=${evt.value}"

    if (isPresenceActive()) {
        clearOffDeadline()
    } else {
        scheduleOffDelay()
    }

    ensureTargetState("presence event")
}

def controlledSwitchHandler(evt) {
    logDebug "Controlled switch event: ${evt.device} ${evt.value}"

    Long ignoreUntil = safeLong(atomicState.ignoreSwitchEventsUntilMs, null)
    if (atomicState.multiToggleInProgress || (ignoreUntil && now() < ignoreUntil)) {
        logDebug "Ignoring controlled switch event during multi-toggle sequence"
        return
    }

    ensureTargetState("controlled switch event")
}

def turnOffAfterDelay() {
    if (isPresenceActive()) {
        logDebug "Off delay expired, but presence is active again"
        clearOffDeadline()
        ensureTargetState("off delay expired")
        return
    }

    Long deadline = state.offDeadlineMs as Long
    if (deadline && now() < deadline) {
        Integer remainingSeconds = Math.max(1, Math.ceil((deadline - now()) / 1000.0) as Integer)
        logDebug "Off delay fired early; rescheduling for ${remainingSeconds} seconds"
        runIn(remainingSeconds, "turnOffAfterDelay", [overwrite: true])
        return
    }

    state.offDeadlineMs = null
    ensureTargetState("off delay expired")
}

private void ensureTargetState(String reason) {
    if (shouldLightsBeOn()) {
        ensureLightsOn(reason)
    } else {
        ensureLightsOff(reason)
    }
}

private void ensureLightsOn(String reason) {
    Integer level = currentDimmerLevel()
    logDebug "Ensuring lights are on for ${reason}; dimmer level=${level}"

    selectedDimmers().each { device ->
        Integer currentLevel = safeInteger(device.currentValue("level"), null)
        if (device.currentValue("switch") != "on" || currentLevel != level) {
            logDebug "Setting ${device} to ${level}"
            device.setLevel(level)
        }
    }

    selectedSingleToggleSwitches().each { device ->
        if (device.currentValue("switch") != "on") {
            logDebug "Turning on ${device}"
            device.on()
        }
    }

    List switchesToToggle = selectedMultiToggleSwitches().findAll { device ->
        !atomicState.multiToggleCycleActive || device.currentValue("switch") != "on"
    }

    if (switchesToToggle) {
        runMultiToggleSequence(switchesToToggle)
        atomicState.multiToggleCycleActive = true
    }
}

private void ensureLightsOff(String reason) {
    logDebug "Ensuring lights are off for ${reason}"
    atomicState.multiToggleCycleActive = false

    controlledDevices().each { device ->
        if (device.currentValue("switch") != "off") {
            logDebug "Turning off ${device}"
            device.off()
        }
    }
}

private void runMultiToggleSequence(List devices) {
    Integer dwellMs = multiToggleDwellMilliseconds()
    List devicesCurrentlyOn = devices.findAll { device -> device.currentValue("switch") == "on" }
    List devicesCurrentlyOff = devices.findAll { device -> device.currentValue("switch") != "on" }

    atomicState.multiToggleInProgress = true
    atomicState.ignoreSwitchEventsUntilMs = now() + (dwellMs * 2L) + 5000L

    try {
        logDebug "Starting multi-toggle sequence for ${devices*.displayName}; dwell=${dwellMs}ms; on=${devicesCurrentlyOn*.displayName}; off=${devicesCurrentlyOff*.displayName}"

        devicesCurrentlyOn.each { it.off() }
        devicesCurrentlyOff.each { it.on() }
        pauseExecution(dwellMs)

        devicesCurrentlyOn.each { it.on() }
        if (devicesCurrentlyOff) {
            devicesCurrentlyOff.each { it.off() }
            pauseExecution(dwellMs)
            devicesCurrentlyOff.each { it.on() }
        }
    } finally {
        atomicState.multiToggleInProgress = false
        atomicState.ignoreSwitchEventsUntilMs = now() + 5000L
    }
}

private Boolean shouldLightsBeOn() {
    if (isPresenceActive()) {
        return true
    }

    Long deadline = state.offDeadlineMs as Long
    return deadline && now() < deadline
}

private Boolean isPresenceActive() {
    Boolean motionActive = motionDevice && motionDevice.currentValue("motion") == "active"
    Boolean presencePresent = presenceDevice && presenceDevice.currentValue("presence") == "present"
    return motionActive || presencePresent
}

private void scheduleOffDelay() {
    Integer delaySeconds = offDelaySecondsValue()
    Long deadline = now() + (delaySeconds * 1000L)
    state.offDeadlineMs = deadline

    if (delaySeconds <= 0) {
        turnOffAfterDelay()
    } else {
        logDebug "Scheduling off check in ${delaySeconds} seconds"
        runIn(delaySeconds, "turnOffAfterDelay", [overwrite: true])
    }
}

private void clearOffDeadline() {
    state.offDeadlineMs = null
    unschedule("turnOffAfterDelay")
}

private Integer currentDimmerLevel() {
    Integer configuredLevel = isDaylightNow() ? safeInteger(dayLevel, 100) : safeInteger(nightLevel, 25)
    return clamp(configuredLevel, 1, 100)
}

private Boolean isDaylightNow() {
    Date current = new Date()
    Date dayStartTime = resolveBoundary(dayStart ?: "sunrise", current)
    Date nightStartTime = resolveBoundary(nightStart ?: "sunset", current)

    Long currentMs = current.time
    Long dayMs = dayStartTime.time
    Long nightMs = nightStartTime.time

    if (dayMs == nightMs) {
        return true
    }

    if (dayMs < nightMs) {
        return currentMs >= dayMs && currentMs < nightMs
    }

    return currentMs >= dayMs || currentMs < nightMs
}

private Date resolveBoundary(String boundary, Date current) {
    if (boundary == "sunrise" || boundary == "sunset") {
        Map sunTimes = getSunriseAndSunset()
        return sunTimes[boundary] as Date
    }

    return timeToday(boundary, location.timeZone)
}

private List controlledDevices() {
    return uniqueDevices(selectedDimmers() + selectedSingleToggleSwitches() + selectedMultiToggleSwitches())
}

private List selectedDimmers() {
    return dimmerDevices ? dimmerDevices as List : []
}

private List selectedSingleToggleSwitches() {
    List selectedSwitches = singleToggleSwitches ? singleToggleSwitches as List : []
    List excludedIds = (selectedDimmers() + selectedMultiToggleSwitches()).collect { device -> device.id as String }
    return selectedSwitches.findAll { device -> !excludedIds.contains(device.id as String) }
}

private List selectedMultiToggleSwitches() {
    List selectedSwitches = multiToggleSwitches ? multiToggleSwitches as List : []
    List dimmerIds = selectedDimmers().collect { device -> device.id as String }
    return selectedSwitches.findAll { device -> !dimmerIds.contains(device.id as String) }
}

private List uniqueDevices(List devices) {
    Map byId = [:]
    devices.each { device ->
        byId[device.id as String] = device
    }
    return byId.values() as List
}

private Boolean configurationComplete() {
    Boolean hasPresenceSource = motionDevice || presenceDevice
    Boolean hasControlledLight = (dimmerDevices && dimmerDevices.size() > 0) ||
        (singleToggleSwitches && singleToggleSwitches.size() > 0) ||
        (multiToggleSwitches && multiToggleSwitches.size() > 0)
    return hasPresenceSource && hasControlledLight
}

private Integer offDelaySecondsValue() {
    return Math.max(0, safeInteger(offDelaySeconds, 300))
}

private Integer multiToggleDwellMilliseconds() {
    return clamp(safeInteger(multiToggleDwellMs, 500), 0, 60000)
}

private Integer safeInteger(value, Integer fallback) {
    if (value == null) {
        return fallback
    }

    try {
        return value as Integer
    } catch (Exception ignored) {
        return fallback
    }
}

private Long safeLong(value, Long fallback) {
    if (value == null) {
        return fallback
    }

    try {
        return value as Long
    } catch (Exception ignored) {
        return fallback
    }
}

private Integer clamp(Integer value, Integer minimum, Integer maximum) {
    Integer result = value
    if (result == null) {
        result = minimum
    }
    return Math.max(minimum, Math.min(maximum, result))
}

private Map timeBoundaryOptions() {
    Map options = [
        sunrise: "Sunrise",
        sunset: "Sunset"
    ]

    (0..23).each { hour ->
        String value = String.format("%02d:00", hour)
        options[value] = formatHour(hour)
    }

    return options
}

private String formatHour(Integer hour) {
    Integer displayHour = hour % 12
    if (displayHour == 0) {
        displayHour = 12
    }

    String suffix = hour < 12 ? "AM" : "PM"
    return "${displayHour}:00 ${suffix}"
}

private void logDebug(String message) {
    if (debugLogging) {
        log.debug message
    }
}

private String configurationTitle() {
    return app?.label ?: "Configuration Set"
}
