# Narrow Map Pane marker visibility

The operator confirmed that expanding the iPad Map Pane revealed the missing drone icon. In the 19:17 recording, the aircraft name and telemetry labels were present while the drone glyph was absent. Android did not reproduce the problem.

Apple's aircraft annotation used an 800 by 360 point canvas with its icon in a child image view. This exceeds the narrow pane's width. The correction uses the rendered marker as MKAnnotationView's native image with 50-point bounds (34 inset), keeping labels and camera rays outside unclipped bounds. Label layout still uses the aircraft anchor. Annotation hit testing retains access to external labels; artifact taps defer to actual annotation hit regions instead of the oversized frame.

This removes oversized marker geometry from MapKit's placement and clipping behavior. It does not change coordinates or altitude acceptance. The user's width test supports the layout diagnosis; narrow-pane field validation of the correction is still required.

Apple build 226 includes this change plus build 225's bounded AOL refresh fix. Android build 225 contains the AOL fix and keeps its existing marker renderer.
