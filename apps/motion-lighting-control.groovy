definition(
    name: "Motion Lighting Control",
    namespace: "ejtbrown",
    author: "Eric Brown",
    description: "Controls multiple motion lighting configuration sets.",
    category: "Convenience",
    iconUrl: "https://raw.githubusercontent.com/ejtbrown/lightingcontrol/main/resources/motion-lighting-control.png",
    iconX2Url: "https://raw.githubusercontent.com/ejtbrown/lightingcontrol/main/resources/motion-lighting-control.png",
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "Motion Lighting Control", install: true, uninstall: true) {
        section("Configuration sets") {
            paragraph "Create one configuration set for each room or area."

            app name: "configurationSets",
                appName: "Motion Lighting Control Configuration Set",
                namespace: "ejtbrown",
                title: "Add a configuration set",
                multiple: true
        }
    }
}

def installed() {
    log.info "Installed ${app.name}"
    initialize()
}

def updated() {
    log.info "Updated ${app.name}"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    Integer setCount = childApps ? childApps.size() : 0
    log.info "Initialized ${app.name} with ${setCount} configuration set${setCount == 1 ? '' : 's'}"
}

def presenceSourceHandler(evt) {
    log.warn "Ignoring legacy presence event on parent app. Open and save each configuration set if this continues."
}

def controlledSwitchHandler(evt) {
    log.warn "Ignoring legacy switch event on parent app. Open and save each configuration set if this continues."
}

def turnOffAfterDelay() {
    log.warn "Ignoring legacy scheduled off check on parent app."
}
