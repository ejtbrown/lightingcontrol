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
    state.offDeadlineMs = null
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

    ensureMultiToggleTrackerStateVersion()

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
    recordControlledSwitchEvent(evt)

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
    atomicState.multiToggleCycleActive = false

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
    ensureMultiToggleTrackerStateVersion()

    String currentMode = knownMultiToggleMode(device)
    Boolean isOn = device.currentValue("switch") == "on"

    if (isOn) {
        if (!currentMode) {
            logDebug "Assuming ${device} is already in ${desiredMode} mode because it is on"
            setKnownMultiToggleMode(device, desiredMode, "assumed")
            return
        }

        if (currentMode == desiredMode) {
            logDebug "${device} is already on in ${desiredMode} mode"
            return
        }

        logDebug "Switching ${device} from ${currentMode} to ${desiredMode} mode while on"
        runMultiToggleFromOn(device, desiredMode)
        return
    }

    if (!currentMode) {
        logDebug "No known mode for ${device}; establishing ${desiredMode} mode"
        if (desiredMode == "night") {
            runMultiToggleFromOff(device, desiredMode, currentMode)
        } else {
            turnMultiToggleOnPreservingMode(device, desiredMode)
        }
        return
    }

    if (currentMode == desiredMode) {
        turnMultiToggleOnPreservingMode(device, desiredMode)
        return
    }

    if (isWithinModeSwitchDwell(device)) {
        logDebug "Turning on ${device} within mode switch dwell to change ${currentMode} mode to ${desiredMode} mode"
        turnMultiToggleOnExpectingMode(device, desiredMode)
        return
    }

    logDebug "Running multi-toggle sequence for ${device} to change ${currentMode} mode to ${desiredMode} mode"
    runMultiToggleFromOff(device, desiredMode, currentMode)
}

private void turnMultiToggleOnPreservingMode(device, String desiredMode) {
    Integer waitMs = remainingModeSwitchDwellMs(device)
    if (waitMs > 0) {
        Long eventSerial = multiToggleEventSerial(device)
        logDebug "Waiting ${waitMs}ms before turning on ${device} to preserve ${desiredMode} mode"
        pauseExecution(waitMs)

        if (multiToggleEventSerial(device) != eventSerial) {
            logDebug "${device} changed while waiting to preserve ${desiredMode} mode; re-evaluating"
            ensureMultiToggleSwitchOn(device, desiredMode)
            return
        }
    }

    if (device.currentValue("switch") == "on") {
        String currentMode = knownMultiToggleMode(device)
        if (currentMode == desiredMode) {
            logDebug "${device} turned on in ${desiredMode} mode while waiting; no on command needed"
            return
        }

        logDebug "${device} turned on in ${currentMode ?: 'unknown'} mode while waiting; switching to ${desiredMode} mode"
        runMultiToggleFromOn(device, desiredMode)
        return
    }

    logDebug "Turning on ${device} in ${desiredMode} mode"
    turnMultiToggleOnPreservingKnownMode(device, desiredMode)
}

private void turnMultiToggleOnPreservingKnownMode(device, String desiredMode) {
    device.on()
    setKnownMultiToggleMode(device, desiredMode, "app-preserved")
}

private void turnMultiToggleOnExpectingMode(device, String expectedMode) {
    setExpectedMultiToggleMode(device, expectedMode)
    device.on()
    setKnownMultiToggleMode(device, expectedMode)
}

private void runMultiToggleFromOn(device, String desiredMode) {
    Integer dwellMs = multiToggleDwellMilliseconds()

    beginMultiToggleCommandWindow(dwellMs + 5000)
    try {
        logDebug "Starting on-state multi-toggle sequence for ${device}; dwell=${dwellMs}ms; desiredMode=${desiredMode}"
        recordMultiToggleOff(device, now())
        device.off()
        pauseExecution(dwellMs)
        turnMultiToggleOnExpectingMode(device, desiredMode)
    } finally {
        endMultiToggleCommandWindow()
    }
}

private void runMultiToggleFromOff(device, String desiredMode, String currentMode) {
    Integer dwellMs = multiToggleDwellMilliseconds()
    Integer waitMs = remainingModeSwitchDwellMs(device)
    if (waitMs > 0) {
        Long eventSerial = multiToggleEventSerial(device)
        logDebug "Waiting ${waitMs}ms before starting multi-toggle sequence for ${device}"
        pauseExecution(waitMs)

        if (multiToggleEventSerial(device) != eventSerial) {
            logDebug "${device} changed while waiting to start a multi-toggle sequence; re-evaluating ${desiredMode} mode"
            ensureMultiToggleSwitchOn(device, desiredMode)
            return
        }
    }

    if (device.currentValue("switch") == "on") {
        logDebug "${device} turned on while waiting to start a multi-toggle sequence"
        ensureMultiToggleSwitchOn(device, desiredMode)
        return
    }

    beginMultiToggleCommandWindow((dwellMs * 2) + 5000)

    try {
        logDebug "Starting off-state multi-toggle sequence for ${device}; dwell=${dwellMs}ms; currentMode=${currentMode ?: 'unknown'}; desiredMode=${desiredMode}"
        if (currentMode) {
            setExpectedMultiToggleMode(device, currentMode)
        }
        device.on()
        pauseExecution(dwellMs)

        recordMultiToggleOff(device, now())
        device.off()
        pauseExecution(dwellMs)

        turnMultiToggleOnExpectingMode(device, desiredMode)
    } finally {
        endMultiToggleCommandWindow()
    }
}

private void beginMultiToggleCommandWindow(Integer durationMs) {
    atomicState.multiToggleInProgress = true
    atomicState.ignoreSwitchEventsUntilMs = now() + Math.max(0, durationMs as Integer)
}

private void endMultiToggleCommandWindow() {
    atomicState.multiToggleInProgress = false
    atomicState.ignoreSwitchEventsUntilMs = now() + 5000L
}

private void recordControlledSwitchEvent(evt) {
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

    String expectedMode = expectedMultiToggleMode(evt.device)
    if (expectedMode) {
        setKnownMultiToggleMode(evt.device, expectedMode)
        clearExpectedMultiToggleMode(evt.device)
        logDebug "Recorded ${evt.device} as ${expectedMode} mode from app command"
        return
    }

    String currentMode = knownMultiToggleMode(evt.device)
    if (currentMode && isWithinModeSwitchDwell(evt.device)) {
        String newMode = oppositeMultiToggleMode(currentMode)
        setKnownMultiToggleMode(evt.device, newMode, "event")
        logDebug "Recorded ${evt.device} as ${newMode} mode from on event inside mode switch dwell"
    }
}

private Boolean isSelectedMultiToggleSwitch(device) {
    String deviceId = device.id as String
    return selectedMultiToggleSwitches().any { selected -> (selected.id as String) == deviceId }
}

private String knownMultiToggleMode(device) {
    ensureMultiToggleTrackerStateVersion()

    String storedMode = atomicState[multiToggleModeKey(device)] as String
    String storedSource = multiToggleModeSource(device)

    if (storedMode && storedSource && !modeSourceShouldInferFromHistory(storedSource)) {
        return storedMode
    }

    String inferredMode = inferMultiToggleModeFromHistory(device)

    if (inferredMode && (!storedMode || !storedSource || modeSourceShouldInferFromHistory(storedSource))) {
        logDebug "Inferred ${device} as ${inferredMode} mode from switch history"
        setKnownMultiToggleMode(device, inferredMode, "history")
        return inferredMode
    }

    return storedMode
}

private Boolean modeSourceShouldInferFromHistory(String source) {
    return !source || source == "assumed" || source == "app-preserved"
}

private void ensureMultiToggleTrackerStateVersion() {
    Integer version = safeInteger(atomicState.multiToggleTrackerStateVersion, 0)
    if (version >= 2) {
        return
    }

    selectedMultiToggleSwitches().each { device ->
        if (atomicState[multiToggleModeKey(device)]) {
            atomicState[multiToggleModeSourceKey(device)] = "assumed"
        }
        clearExpectedMultiToggleMode(device)
    }

    atomicState.multiToggleTrackerStateVersion = 2
    logDebug "Reset multi-toggle mode confidence after tracker update"
}

private void setKnownMultiToggleMode(device, String mode) {
    setKnownMultiToggleMode(device, mode, "app")
}

private void setKnownMultiToggleMode(device, String mode, String source) {
    if (mode) {
        atomicState[multiToggleModeKey(device)] = mode
        atomicState[multiToggleModeSourceKey(device)] = source ?: "app"
    }
}

private String multiToggleModeSource(device) {
    return atomicState[multiToggleModeSourceKey(device)] as String
}

private String expectedMultiToggleMode(device) {
    return atomicState[multiToggleExpectedModeKey(device)] as String
}

private void setExpectedMultiToggleMode(device, String mode) {
    if (mode) {
        atomicState[multiToggleExpectedModeKey(device)] = mode
    }
}

private void clearExpectedMultiToggleMode(device) {
    atomicState[multiToggleExpectedModeKey(device)] = null
}

private String oppositeMultiToggleMode(String mode) {
    return mode == "night" ? "day" : "night"
}

private String multiToggleModeKey(device) {
    return "multiToggleMode_${device.id}"
}

private String multiToggleModeSourceKey(device) {
    return "multiToggleModeSource_${device.id}"
}

private String multiToggleExpectedModeKey(device) {
    return "multiToggleExpectedMode_${device.id}"
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

private Boolean isWithinModeSwitchDwell(device) {
    return remainingModeSwitchDwellMs(device) > 0
}

private Integer remainingModeSwitchDwellMs(device) {
    Long lastOffMs = lastMultiToggleOffMs(device)
    if (!lastOffMs) {
        return 0
    }

    Long elapsedMs = now() - lastOffMs
    Long dwellMs = modeSwitchDwellMilliseconds() as Long
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

private String inferMultiToggleModeFromHistory(device) {
    try {
        List events = switchEventsFromHistory(device, 24L * 60L * 60L * 1000L, 80).sort { event ->
            event.date.time
        }
        String mode = null

        events.eachWithIndex { event, Integer index ->
            if (event.value != "on") {
                return
            }

            if (isFinalOnInMultiTogglePattern(events, index)) {
                mode = desiredMultiToggleModeAt(event.date)
                return
            }

            def previousEvent = index > 0 ? events[index - 1] : null
            if (mode && previousEvent?.value == "off" && isInsideModeSwitchDwell(previousEvent.date.time, event.date.time)) {
                mode = oppositeMultiToggleMode(mode)
            }
        }

        return mode
    } catch (Exception e) {
        logDebug "Unable to infer multi-toggle mode for ${device}: ${e.message}"
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

private Boolean isFinalOnInMultiTogglePattern(List events, Integer index) {
    if (index < 2 || events[index].value != "on") {
        return false
    }

    def previousOff = events[index - 1]
    def previousOn = events[index - 2]
    Long dwellToleranceMs = (multiToggleDwellMilliseconds() + 2000L) as Long

    return previousOff.value == "off" &&
        previousOn.value == "on" &&
        (events[index].date.time - previousOff.date.time) <= dwellToleranceMs &&
        (previousOff.date.time - previousOn.date.time) <= dwellToleranceMs
}

private Boolean isInsideModeSwitchDwell(Long offMs, Long onMs) {
    return offMs && onMs && (onMs - offMs) <= (modeSwitchDwellMilliseconds() as Long)
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
    return isDaylightAt(new Date())
}

private String desiredMultiToggleModeAt(Date current) {
    return isDaylightAt(current) ? "day" : "night"
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

private Integer modeSwitchDwellSecondsValue() {
    return clamp(safeInteger(modeSwitchDwellSeconds, 4), 0, 300)
}

private Integer modeSwitchDwellMilliseconds() {
    return modeSwitchDwellSecondsValue() * 1000
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
