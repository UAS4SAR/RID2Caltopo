# Wired controller video

Android and Apple (including the iPad app running on Apple-silicon Macs) display
the complete Wi-Fi controller RTMP URL and add a separate Ethernet URL when
a wired link has a usable IPv4 address. Plugging or unplugging an adapter updates
the Ethernet entry without replacing the Wi-Fi URL. No copy buttons are provided.
Cellular and VPN addresses are not advertised as controller destinations.
The controller link does not need Internet access; Wi-Fi or cellular can remain
available for Tracker and maps. The app does not bind all traffic to Ethernet.

Connect supported Ethernet adapters at both ends and give the controller and
R2C device addresses on the same subnet. A router providing DHCP is one option;
a direct cable may require manual address configuration in the operating
systems. R2C does not configure DHCP, static addresses, or Internet sharing.
An adapter without an IPv4 address is not yet a usable controller connection.

Enter the displayed RTMP address in DJI Pilot 2 with the actual drone designator
in place of `<droneDesig>`. Start the controller stream and confirm video and
telemetry in R2C. After changing connections, update the URL in Pilot 2 and
restart its stream; an existing TCP stream cannot migrate between addresses.
When Ethernet and Wi-Fi are both connected, choose the URL for the controller's
connection. Wi-Fi remains listed first; Ethernet is an additional option.

A USB cable does not by itself provide Ethernet. The development-only ADB
reverse-port tunnel tested on the RC Plus 2 is separate from this feature and
does not establish that Pilot 2 supports streaming without a recognized network.

Physical qualification pending: adapter recognition on Android/iPad/Mac,
local-only link with Wi-Fi Internet active, cable removal/reconnect and DHCP
changes, actual Pilot 2 video/SEI reception, and D-RTK 3 relay operation.
