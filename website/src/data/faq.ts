// FAQ source: README FAQ and the in-app Help page. Plain text answers (also used for FAQPage JSON-LD).
export interface Faq { q: string; a: string }

export const FAQS: Faq[] = [
  {
    q: 'How do I mirror my Android phone to my Tesla?',
    a: 'Install WebMirror on the phone, turn on the phone\'s hotspot and join it from the Tesla (turn on "Remain connected in Drive"). Tap Start in WebMirror and open the address it shows in the Tesla browser. Enter the PIN once.',
  },
  {
    q: 'Does it need an app on the Tesla, a cable or an adapter?',
    a: 'No. The Tesla uses its built-in browser. Nothing is installed in the car, and no extra hardware or account is needed.',
  },
  {
    q: 'Which Tesla models work?',
    a: 'Any Tesla whose browser supports modern video decoding (WebCodecs). It was developed and tested on a Model S with MCU2. MCU3 cars (Model 3, Y, S and X with the AMD Ryzen computer) have a faster browser and should work as well.',
  },
  {
    q: 'Is there sound?',
    a: 'Yes. Sound can play through the Tesla browser in sync with the picture, or over Bluetooth with an adjustable delay. Some apps, for example Netflix, block sound capture and stay silent.',
  },
  {
    q: 'Can I use the Tesla touchscreen to control the phone?',
    a: 'Yes, if you allow it. Taps, swipes and pinch zoom on the Tesla screen are sent to the phone. You can also type with the phone keyboard in the picture or, on Android 13 or newer, with the Tesla browser\'s own keyboard.',
  },
  {
    q: 'Does it work with an iPhone?',
    a: 'No. WebMirror is an Android app and needs Android 10 or newer.',
  },
  {
    q: 'Does it work with Android Auto or Apple CarPlay?',
    a: 'WebMirror is not Android Auto or CarPlay. It shows the whole phone screen, so any app you open on the phone appears on the Tesla screen.',
  },
  {
    q: 'Can I watch while driving?',
    a: 'WebMirror is for passengers. The driver must never watch or operate the mirrored screen while driving. Follow the traffic laws of your country.',
  },
  {
    q: 'Why do I need a secure (https) address?',
    a: 'The car browser plays smooth video only on an https address, because video decoding (WebCodecs) needs a secure page. On plain http WebMirror falls back to JPEG pictures, which is much less smooth (about 10 to 15 fps). The app can set up a free certificate for you in about two minutes.',
  },
  {
    q: 'Does it use my mobile data?',
    a: 'Mirroring stays on your local network, between the phone and the car over the phone\'s hotspot. The app uses the internet only to get or renew a free certificate and, in the GitHub version, to check for updates.',
  },
  {
    q: 'Is it free? Who can see my screen?',
    a: 'WebMirror is free and open source (MIT license). It has no accounts, no analytics and no advertising, and the author receives no data. Only browsers you pair with the PIN can watch the phone.',
  },
  {
    q: 'Does it work on other devices?',
    a: 'Yes. Any device with a modern browser on the same network works, such as a laptop, tablet or TV. It is optimised for the Tesla browser.',
  },
  {
    q: 'Why is the screen black in some apps?',
    a: 'Some apps, for example banking, video and streaming apps, block screen capture on purpose. This cannot be changed.',
  },
  {
    q: 'Is it on Google Play?',
    a: 'Not yet. Google Play is planned. For now, download the APK from GitHub Releases.',
  },
];
