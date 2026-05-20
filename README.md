# Lighting Control

Hubitat app for controlling dimmers and multi-toggle switches from motion or
presence sources.

## Behavior

- Create one configuration set per room or area.
- Give each configuration set its own name.
- In each configuration set, select one motion sensor, one presence sensor, or
  both.
- In each configuration set, select one or more dimmers, single-toggle
  switches, and/or multi-toggle switches.
- Choose when daylight starts and when nighttime starts. Each boundary can be
  a fixed hour, sunrise, or sunset.
- Dimmers are set to the configured day or night level when the app turns them
  on.
- Single-toggle switches are turned on and off directly, with no dwell delay or
  special sequence.
- Multi-toggle switches track their inferred day/night mode. The app uses the
  configured mode switch dwell time and recent switch-off history before
  turning them on, so a room re-entry just after shutoff does not accidentally
  flip the fixture into the wrong mode. When a mode change is needed, the app
  uses the configured multi-toggle dwell delay between on/off commands. Delayed
  mode-preserving commands are re-evaluated if the switch changes while the app
  is waiting.
- Lights remain on while motion or presence is active. When motion or presence
  is absent, the app leaves them on for the configured off delay, then turns
  them off.
- The app subscribes to the controlled devices' switch states and corrects them
  back to the expected state.

## Hubitat

Target platform tested against Hubitat Elevation C-8 Pro platform `2.4.2.157`.
