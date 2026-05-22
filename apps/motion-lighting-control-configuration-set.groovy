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

                input name: "modeSwitchDwellSeconds",
                    type: "number",
                    title: "Mode switch dwell time, seconds",
                    defaultValue: 4,
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
    clearOffDeadlineState()
    initialize()
}

def initialize() {
    atomicState.multiToggleInProgress = false
    atomicState.ignoreSwitchEventsUntilMs = null

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
        ensureTargetState("initialize")
    } else if (anyControlledDeviceOn()) {
        scheduleOffDelay()
    } else {
        clearOffDeadline()
    }
}

def presenceSourceHandler(evt) {
    logDebug "Presence source event: ${evt.device} ${evt.name}=${evt.value}"

    if (isPresenceActive()) {
        clearOffDeadline()
    } else if (anyControlledDeviceOn()) {
        scheduleOffDelay()
    } else {
        clearOffDeadline()
    }

    ensureTargetState("presence event")
}

def controlledSwitchHandler(evt) {
    logDebug "Controlled switch event: ${evt.device} ${evt.value}"

    Long ignoreUntil = safeLong(atomicState.ignoreSwitchEventsUntilMs, null)
    Long currentMs = now()
    Boolean commandWindowActive = ignoreUntil && currentMs < ignoreUntil
    if (!commandWindowActive && atomicState.multiToggleInProgress) {
        atomicState.multiToggleInProgress = false
    }

    recordControlledSwitchEvent(evt, commandWindowActive)

    if (commandWindowActive) {
        logDebug "Ignoring controlled switch event during multi-toggle sequence"
        return
    }

    if (isSelectedMultiToggleSwitch(evt.device) && evt.value == "off" && shouldLightsBeOn()) {
        scheduleManualMultiToggleRecheck(evt.device)
        return
    }

    ensureTargetState("controlled switch event")
}

def recheckAfterManualMultiToggle() {
    ensureTargetState("manual multi-toggle recheck")
}

def turnOffAfterDelay() {
    if (isPresenceActive()) {
        logDebug "Off delay expired, but presence is active again"
        clearOffDeadline()
        ensureTargetState("off delay expired")
        return
    }

    Long deadline = offDeadlineMs()
    if (deadline && now() < deadline) {
        Integer remainingSeconds = Math.max(1, Math.ceil((deadline - now()) / 1000.0) as Integer)
        logDebug "Off delay fired early; rescheduling for ${remainingSeconds} seconds"
        runIn(remainingSeconds, "turnOffAfterDelay", [overwrite: true])
        return
    }

    clearOffDeadlineState()
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
    Boolean daylight = isDaylightNow()
    logDebug "Ensuring lights are on for ${reason}; daylight=${daylight}; dimmer level=${level}"

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

    String desiredMode = daylight ? "day" : "night"
    selectedMultiToggleSwitches().each { device ->
        ensureMultiToggleSwitchOn(device, desiredMode)
    }
}

private void ensureLightsOff(String reason) {
    logDebug "Ensuring lights are off for ${reason}"

    controlledDevices().each { device ->
        if (device.currentValue("switch") != "off") {
            logDebug "Turning off ${device}"
            if (isSelectedMultiToggleSwitch(device)) {
                recordMultiToggleOff(device, now())
            }
            device.off()
        }
    }
}

private void ensureMultiToggleSwitchOn(device, String desiredMode) {
    Boolean isOn = device.currentValue("switch") == "on"

    if (isOn) {
        logDebug "${device} is already on; leaving current mode unchanged"
        return
    }

    if (desiredMode == "night") {
        turnMultiToggleOnForNight(device)
        return
    }

    turnMultiToggleOnForDay(device)
}

private void turnMultiToggleOnForDay(device) {
    if (!waitForModeSwitchDwell(device, "turning on ${device} in day mode")) {
        return
    }

    if (!shouldLightsBeOn()) {
        logDebug "Not turning on ${device}; lights are no longer expected to be on"
        return
    }

    if (device.currentValue("switch") == "on") {
        logDebug "${device} turned on while waiting; leaving current mode unchanged"
        return
    }

    logDebug "Turning on ${device} once for day mode"
    beginMultiToggleCommandWindow(5000)
    try {
        device.on()
    } finally {
        endMultiToggleCommandWindow()
    }
}

private void turnMultiToggleOnForNight(device) {
    if (!waitForModeSwitchDwell(device, "starting night on/off/on sequence for ${device}")) {
        return
    }

    if (!shouldLightsBeOn()) {
        logDebug "Not starting night on/off/on sequence for ${device}; lights are no longer expected to be on"
        return
    }

    if (device.currentValue("switch") == "on") {
        logDebug "${device} turned on while waiting; leaving current mode unchanged"
        return
    }

    Integer dwellMs = quickToggleDwellMilliseconds()
    beginMultiToggleCommandWindow((dwellMs * 2) + 5000)

    try {
        logDebug "Starting night on/off/on sequence for ${device}; dwell=${dwellMs}ms"
        device.on()
        pauseExecution(dwellMs)

        recordMultiToggleOff(device, now())
        device.off()
        pauseExecution(dwellMs)

        if (!shouldLightsBeOn()) {
            logDebug "Stopping night on/off/on sequence for ${device}; lights are no longer expected to be on"
            return
        }

        device.on()
    } finally {
        endMultiToggleCommandWindow()
    }
}

private Boolean waitForModeSwitchDwell(device, String action) {
    Integer waitMs = remainingModeSwitchDwellMs(device)
    if (waitMs <= 0) {
        return true
    }

    Long eventSerial = multiToggleEventSerial(device)
    logDebug "Waiting ${waitMs}ms before ${action}"
    pauseExecution(waitMs)

    if (multiToggleEventSerial(device) != eventSerial) {
        logDebug "${device} changed while waiting before ${action}"
        return false
    }

    return true
}

private void beginMultiToggleCommandWindow(Integer durationMs) {
    atomicState.multiToggleInProgress = true
    atomicState.ignoreSwitchEventsUntilMs = now() + Math.max(0, durationMs as Integer)
}

private void endMultiToggleCommandWindow() {
    atomicState.multiToggleInProgress = false
    atomicState.ignoreSwitchEventsUntilMs = now() + 5000L
}

private void recordControlledSwitchEvent(evt, Boolean commandWindowActive) {
    if (!evt?.device || !isSelectedMultiToggleSwitch(evt.device)) {
        return
    }

    bumpMultiToggleEventSerial(evt.device)

    if (evt.value == "off") {
        recordMultiToggleOff(evt.device, eventTimeMs(evt))
        return
    }

    if (evt.value != "on") {
        return
    }

    if (commandWindowActive) {
        logDebug "Recorded ${evt.device} on event during app command window"
        return
    }

    logDebug "Recorded ${evt.device} on event"
}

private Boolean isSelectedMultiToggleSwitch(device) {
    if (!device) {
        return false
    }

    String deviceId = device.id as String
    return selectedMultiToggleSwitches().any { selected -> (selected.id as String) == deviceId }
}

private String multiToggleEventSerialKey(device) {
    return "multiToggleEventSerial_${device.id}"
}

private String multiToggleLastOffKey(device) {
    return "multiToggleLastOffMs_${device.id}"
}

private Long multiToggleEventSerial(device) {
    return safeLong(atomicState[multiToggleEventSerialKey(device)], 0L)
}

private void bumpMultiToggleEventSerial(device) {
    atomicState[multiToggleEventSerialKey(device)] = multiToggleEventSerial(device) + 1L
}

private void recordMultiToggleOff(device, Long eventMs) {
    atomicState[multiToggleLastOffKey(device)] = eventMs ?: now()
}

private Long lastMultiToggleOffMs(device) {
    Long storedMs = safeLong(atomicState[multiToggleLastOffKey(device)], null)
    Long historyMs = latestSwitchOffMsFromHistory(device)
    List values = [storedMs, historyMs].findAll { value -> value != null }
    return values ? values.max() as Long : null
}

private Integer remainingModeSwitchDwellMs(device) {
    Long lastOffMs = lastMultiToggleOffMs(device)
    if (!lastOffMs) {
        return 0
    }

    Long elapsedMs = Math.max(0L, now() - lastOffMs)
    Long dwellMs = modeResetDwellMilliseconds() as Long
    return elapsedMs < dwellMs ? Math.max(1, (dwellMs - elapsedMs) as Integer) : 0
}

private Long latestSwitchOffMsFromHistory(device) {
    try {
        Long lookbackMs = ((modeSwitchDwellSecondsValue() + 5) * 1000L) as Long
        List offEventTimes = switchEventsFromHistory(device, lookbackMs, 20).findAll { event ->
            event.value == "off"
        }.collect { event ->
            event.date.time as Long
        }

        return offEventTimes ? offEventTimes.max() as Long : null
    } catch (Exception e) {
        logDebug "Unable to read switch history for ${device}: ${e.message}"
        return null
    }
}

private List switchEventsFromHistory(device, Long lookbackMs, Integer maxEvents) {
    Date since = new Date(now() - lookbackMs)
    def events = device.eventsSince(since, [max: maxEvents])
    return (events ?: []).findAll { event ->
        event.name == "switch" && (event.value == "on" || event.value == "off") && event.date
    }
}

private Long eventTimeMs(evt) {
    try {
        return evt?.date?.time ? evt.date.time as Long : now()
    } catch (Exception ignored) {
        return now()
    }
}

private Boolean shouldLightsBeOn() {
    if (isPresenceActive()) {
        return true
    }

    Long deadline = offDeadlineMs()
    return deadline && now() < deadline
}

private Boolean isPresenceActive() {
    Boolean motionActive = motionDevice && motionDevice.currentValue("motion") == "active"
    Boolean presencePresent = presenceDevice && presenceDevice.currentValue("presence") == "present"
    return motionActive || presencePresent
}

private Boolean anyControlledDeviceOn() {
    return controlledDevices().any { device ->
        device.currentValue("switch") == "on"
    }
}

private void scheduleManualMultiToggleRecheck(device) {
    Integer waitSeconds = Math.max(1, Math.ceil(modeResetDwellMilliseconds() / 1000.0) as Integer)
    logDebug "Delaying correction for ${device} for ${waitSeconds}s to allow a manual multi-toggle"
    runIn(waitSeconds, "recheckAfterManualMultiToggle", [overwrite: true])
}

private void scheduleOffDelay() {
    Integer delaySeconds = offDelaySecondsValue()
    Long deadline = now() + (delaySeconds * 1000L)
    atomicState.offDeadlineMs = deadline

    if (delaySeconds <= 0) {
        turnOffAfterDelay()
    } else {
        logDebug "Scheduling off check in ${delaySeconds} seconds"
        runIn(delaySeconds, "turnOffAfterDelay", [overwrite: true])
    }
}

private void clearOffDeadline() {
    clearOffDeadlineState()
    unschedule("turnOffAfterDelay")
}

private void clearOffDeadlineState() {
    atomicState.offDeadlineMs = null
    state.offDeadlineMs = null
}

private Long offDeadlineMs() {
    Long deadline = safeLong(atomicState.offDeadlineMs, null)
    if (deadline != null) {
        return deadline
    }

    deadline = safeLong(state.offDeadlineMs, null)
    if (deadline != null) {
        atomicState.offDeadlineMs = deadline
    }

    return deadline
}

private Integer currentDimmerLevel() {
    Integer configuredLevel = isDaylightNow() ? safeInteger(dayLevel, 100) : safeInteger(nightLevel, 25)
    return clamp(configuredLevel, 1, 100)
}

private Boolean isDaylightNow() {
    return isDaylightAt(new Date())
}

private Boolean isDaylightAt(Date current) {
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

private Integer quickToggleDwellMilliseconds() {
    Integer dwellMs = multiToggleDwellMilliseconds()
    Integer modeDwellMs = modeSwitchDwellMilliseconds()
    if (modeDwellMs <= 0) {
        return dwellMs
    }

    return Math.min(dwellMs, Math.max(0, modeDwellMs - 100))
}

private Integer modeSwitchDwellSecondsValue() {
    return clamp(safeInteger(modeSwitchDwellSeconds, 4), 0, 300)
}

private Integer modeSwitchDwellMilliseconds() {
    return modeSwitchDwellSecondsValue() * 1000
}

private Integer modeResetDwellMilliseconds() {
    Integer dwellMs = modeSwitchDwellMilliseconds()
    return dwellMs > 0 ? dwellMs + 250 : 0
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
