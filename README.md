# 🔐 TCC-Fingerprint

[![Android](https://img.shields.io/badge/Android-API%2021+-green.svg)](https://developer.android.com/about/versions/android-5.0)
[![OpenCV](https://img.shields.io/badge/OpenCV-4.9.0-blue.svg)](https://opencv.org/)
[![TensorFlow Lite](https://img.shields.io/badge/TensorFlow%20Lite-✓-orange.svg)](https://www.tensorflow.org/lite)
[![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-✓-red.svg)](https://onnxruntime.ai/)
[![License](https://img.shields.io/badge/License-Private-red.svg)](LICENSE)

> A modern, modular Android application for capturing, processing, assessing, and uploading fingerprint images with deep learning-based detection and quality control.

## ✨ Features

- 📱 **Live Camera Preview** with ROI (Region of Interest) overlay
- 🤖 **Deep Learning Detection** using TFLite & ONNX backends
- 🔍 **Quality Assessment** for captured fingerprints
- 🔐 **Secure Authentication** with user registration and verification
- 🌐 **Flexible API Backend** configuration
- 📊 **Real-time Diagnostics** with error feedback and progress indicators
- 🏗️ **Modular Architecture** for easy maintenance and extension
- ⚙️ **Customizable Settings** via SharedPreferences
- 🔄 **Robust Network Operations** with retry logic and async handling
- 📈 **Capture Analytics** with result summaries and upload tracking

## 🚀 Quick Start

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (latest version recommended)
- Android device or emulator (API 21+ required)
- OpenCV, TFLite, and ONNX runtime dependencies (included in project)

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/Tech365/TCC-Fingerprint.git
   cd TCC-Fingerprint
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an existing project"
   - Navigate to the cloned folder and select it
   - Wait for Gradle sync to complete

3. **Configure API endpoints**
   - Edit API URL and network settings via in-app settings
   - Or update `Constants.kt` for compile-time configuration

4. **Build and run**
   - Connect your Android device or start an emulator
   - Click the "Run" button or use `./gradlew assembleDebug`

## 📱 Usage Guide

### 🔐 User Registration
1. Launch the app and navigate to registration
2. Enter user details and capture fingerprints
3. Images are automatically processed and quality-checked
4. Valid fingerprints are sent to your backend API

### ✅ Fingerprint Verification
1. Select verification mode
2. Capture the fingerprint to verify
3. App compares against registered users
4. Returns verification results

### 📤 Image Upload
1. Images pass through configurable quality checks
2. Quality thresholds can be adjusted in settings
3. Successful captures are uploaded to backend
4. Upload progress and status are tracked

### ⚙️ Settings Configuration
- **Backend Configuration**: API URLs, endpoints, timeouts
- **Quality Thresholds**: Adjustable parameters for image acceptance
- **Camera Parameters**: Resolution, focus, exposure settings
- **Debug Mode**: Enable/disable diagnostic features

## 🏗️ Project Architecture

```
app/src/main/java/com/tcc/fingerprint/
├── 📱 ui/                    # Activities and Views
│   ├── MainActivity.kt      # Main application entry point
│   ├── CaptureActivity.kt   # Camera capture interface
│   ├── PreviewActivity.kt   # Image preview and processing
│   └── SettingsActivity.kt  # Configuration interface
├── 🤖 detection/            # Finger detection engines
│   ├── TFLiteDetector.kt    # TensorFlow Lite backend
│   ├── ONNXDetector.kt      # ONNX Runtime backend
│   └── HybridDetector.kt    # Combined approach
├── 🖼️ processing/           # Image processing pipeline
│   ├── FingerprintProcessor.kt  # Main processing logic
│   ├── ImageEnhancer.kt     # Quality improvement
│   └── ROIExtractor.kt      # Region of interest detection
├── 📊 quality/              # Quality assessment
│   ├── QualityChecker.kt    # Image quality evaluation
│   └── ThresholdManager.kt  # Quality thresholds
├── 🌐 network/              # API communication
│   ├── ApiService.kt        # REST API interface
│   ├── NetworkRepository.kt # Data repository
│   └── UploadManager.kt     # File upload handling
├── 💾 data/                 # Data persistence
│   ├── SharedPrefsManager.kt # Settings storage
│   └── DatabaseHelper.kt    # Local database
└── 🛠️ utils/                # Utility classes
    ├── Constants.kt         # Application constants
    ├── FileUtils.kt         # File operations
    └── ValidationUtils.kt   # Input validation
```

## ⚙️ Configuration

### Runtime Configuration
Most settings can be configured through the in-app Settings UI:
- API endpoints and authentication
- Quality thresholds and processing parameters
- Camera settings and capture preferences
- Debug mode and logging options

### Compile-time Configuration
Edit `Constants.kt` for build-time settings:
- Default API URLs
- Network timeouts
- Quality thresholds
- Feature flags

## 🔧 Building

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Clean Build
```bash
./gradlew clean
./gradlew assembleDebug
```

## 🐛 Troubleshooting

### Common Issues

**Gradle Sync Fails**
- Ensure you have the latest Android Studio
- Check internet connection for dependency downloads
- Clear Gradle cache: `./gradlew clean`

**Camera Not Working**
- Verify camera permissions in device settings
- Check if another app is using the camera
- Ensure device supports Camera2 API

**API Connection Issues**
- Verify network connectivity
- Check API endpoint configuration
- Review server logs for errors

**Build Errors**
- Ensure all dependencies are properly included
- Check Android SDK version compatibility
- Verify OpenCV SDK integration

## 🤝 Contributing

We welcome contributions! Here's how you can help:

1. **Fork the repository**
2. **Create a feature branch** (`git checkout -b feature/amazing-feature`)
3. **Commit your changes** (`git commit -m 'Add amazing feature'`)
4. **Push to the branch** (`git push origin feature/amazing-feature`)
5. **Open a Pull Request**

For major changes, please open an issue first to discuss the proposed changes.

## 📄 License

This project is **private** and intended for internal use or evaluation. For public release or distribution, please add an appropriate open source or proprietary license.

## 👨‍💻 Development Team

**Developed by:** [Tech365](https://github.com/Tech365)

## 📞 Support & Contact

- **Technical Support:** Open an issue on GitHub
- **Business Inquiries:** [mustafa@tech365.co.nz](mailto:mustafa@tech365.co.nz)
- **Documentation:** Check the code comments and this README

---

<div align="center">

**Made with ❤️ by Tech365**

*Building the future of biometric authentication*

</div>
