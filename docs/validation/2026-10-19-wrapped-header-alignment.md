# Wrapped workspace header alignment — 2.5.3 (401)

The supplied Android phone screenshot shows the About/title button on the first
line and fullscreen/PiP/bridge/menu controls on a second, left-aligned line. The
requested behavior retains wrapping while keeping the action strip and overflow
menu at the right edge.

Android's FlowRow used SpaceBetween: with a single item on a wrapped line there
was no distributed gap, so the action row started at the left. Its replacement
measures the title and control strip once each. If both fit, the title is placed
at the leading edge and the controls at the trailing edge on the same line. If
they do not fit, the controls move below the title and retain trailing alignment.
The controls remain horizontally scrollable at very narrow widths; initial
scroll position favors the trailing menu. Layout follows actual measured sizes
and font scale instead of a fixed device-width breakpoint.

Apple had the same leading-alignment issue in its narrow ViewThatFits fallback.
The wrapped action row now fills available width with trailing alignment when
it fits; oversized controls retain a trailing-anchored horizontal scroll view.
Wide-screen title/control placement is retained on both platforms.

All new work is bounded native layout measurement. No disk, network, image,
mission or background job behavior changed. The previous aircraft persistence
and Pilot controller-page revision changes remain present.

Validation: Android debug build passes after correcting missing Compose unit
extension imports; Apple signed device build passes. Metadata/copy checks and
canonical notes synchronization pass. Both source configurations are
2.5.3/build 401. This is compile/layout-source verification; no phone visual or
physical-device UI verification is claimed. Only the DJI controller is connected
over ADB, so the phone's updated APK has not been installed.
