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
- Multi-toggle switches turn on directly during daylight hours. Outside the
  daylight window, they use their starting state when the app turns them on. If
  the switch is already on, it is turned off, paused for the configured dwell
  time, and turned back on. If it is off, it is turned on, paused, turned off,
  paused again, and turned on.
- Lights remain on while motion or presence is active. When motion or presence
  is absent, the app leaves them on for the configured off delay, then turns
  them off.
- The app subscribes to the controlled devices' switch states and corrects them
  back to the expected state.

## Hubitat

Target platform tested against Hubitat Elevation C-8 Pro platform `2.4.2.157`.
