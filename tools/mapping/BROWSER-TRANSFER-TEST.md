# A5 Pro ↔ RC Plus 2 browser transfer test

No software is installed on the DJI controller. USB is useful for observing the test, but normal transfers use Wi-Fi.

1. Connect the A5 Pro and controller to the same Wi-Fi. In R2C, connect the incident map, select an assignment's **Map** action, and choose camera/resolution in **Define Map Mission**.
2. Choose **Start controller transfer (QR)**. Keep R2C visible. The panel shows an HTTPS address, six-digit code, QR, certificate fingerprint, and retained-photo count. Only a browser-compatible scanner can open the QR; this prototype does not scan it through the DJI aircraft camera.
3. In the controller's existing browser, open the displayed HTTPS address and enter the six-digit code. If a certificate warning appears, the human operator must confirm that it is the intended tablet. This local test certificate is not publicly trusted; no certificate installation is required by the prototype.
4. Choose **Download boundary KML**. Import the downloaded file in Pilot 2's Flight Route library. The KML contains only the assignment boundary; configure/review the displayed R2C flight parameters in Pilot 2. The ZIP contains an R2C manifest plus KML and is not a Pilot 2 route.
5. Before flying, test the return flow with two original JPEGs already on the controller. Choose **Choose original photos** on the browser page. Confirm both browser and tablet report two verified originals. Select the same files again: the retained count should not increase.
6. Test a larger selection, choose **Pause upload**, then select the same originals again. It should continue from the stored byte positions. Close/reopen R2C if desired: select the assignment again and choose **Resume transfer for this flight** beside the saved-flight record. This gives a fresh URL/code without losing inventory.
7. Only after file-picker access and transfer are repeatable, test a small flight. After landing, use Pilot 2 to download aircraft originals, then select those JPEGs on the same transfer page. Keep visible and thermal flights separate. No flight is started from the page.
8. Choose **Close transfer** when done. Original bytes remain in R2C's private mission storage. This prototype supplies transfer/intake; the same-device reconstruction connection is a subsequent step.

If Pilot 2's originals do not appear in the browser picker, record that as a qualification failure. Do not substitute previews or screenshots. Native Cloud API qualification should be prioritized for route libraries and automatic media upload without this manual selection step.

## Native Pilot 2 qualification

In R2C's transfer panel, expand **Test Pilot 2 Open Platforms connection**. Enter its `/pilot2.html` URL in the controller's **Cloud Services → Open Platforms → Third-Party Cloud Services** URL field. This page only checks native interface availability. Enter the six-digit R2C code to send the Boolean capability result back to the tablet. A certificate prompt must be handled by the human operator. License verification, MQTT registration, route-library APIs and S3-compatible media storage are subsequent implementation/qualification steps; this check is not a working Cloud API platform.
