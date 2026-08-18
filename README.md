# VSense — An Assistive App for the Visually Impaired

![Status](https://img.shields.io/badge/Status-Active-brightgreen) ![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white) ![Powered by](https://img.shields.io/badge/Powered_by-AI-blue)

A smart, voice-controlled Android app that helps visually impaired people navigate their surroundings safely. It acts as an intelligent assistant that can "see" objects, warn about obstacles, and guide the user.

---

## 🌟 What It Can Do

### 🧭 Easy Navigation
*   **🎧 3D Sound Feedback:** The app plays sounds to let you know exactly where objects are around you (left, right, near, or far).
*   **📍 Object Memory:** The app remembers where things are. If you walk past a cup, you can ask "Where is my cup?" and it will tell you.
*   **🔍 Find Mode:** Ask the app to "Find a door" or "Find a chair," and it will guide you to it using sound and vibrations.

### 🧠 Smart Vision
*   **📷 Instant Object Detection:** Uses the phone's camera to instantly spot obstacles and things in your path, without needing the internet.
*   **🖼️ Scene Description:** Ask "Describe what you see," and the app will tell you exactly what the room looks like.
*   **📖 Read Text (OCR):** Point the camera at a sign or book, and it will read the text out loud.
*   **😊 Facial Recognition:** Recognizes people you know when they are nearby and tells you their names.

### 🛡️ Safety First
*   **🎙️ Voice Control:** Control everything using just your voice. No need to look at the screen.
*   **🚨 Fall SOS:** If the phone detects you have fallen, it automatically sends an emergency SOS message to your contacts.
*   **🔦 Manual & Auto-Flashlight:** You can turn the flashlight on and off yourself with a voice command, or let the app do it automatically in the dark so the camera can keep seeing clearly.

---

## 🏗️ How It Works (Diagrams)

### 1. The Big Picture

```mermaid
graph TD
    A[Camera Feed] --> B(Vision Processor)
    B --> C{Offline AI}
    C -->|Fast Detection| D[3D Sound]
    C -->|Guidance| E[Vibration & Navigation]
    C -->|Remember Objects| F[(Memory Database)]
    
    A -->|Ask a Question| G{Cloud AI}
    G -->|Detailed Answer| H[Voice Response]
    
    I[Phone Sensors] --> J[Fall Detection]
    I --> F
```

### 2. How 3D Sound Works

```mermaid
graph LR
    A[See an Object] --> B(Get its Location)
    B -->|Left/Right| C(Sound Direction)
    B -->|Size| D(Sound Volume)
    B -->|Up/Down| E(Sound Pitch)
    C --> F((Your Earphones))
    D --> F
    E --> F
```

### 3. Remembering Objects

```mermaid
sequenceDiagram
    participant Camera
    participant AI
    participant Compass
    participant Database
    participant User
    
    Camera->>AI: Sees a 'Cup'
    Compass->>Database: Saves direction (e.g. East)
    AI->>Database: Saves 'Cup' at East
    Note over Camera,User: User turns around
    User->>Camera: "Where is my cup?"
    Camera->>Database: Look for 'Cup'
    Database-->>Camera: It's at East
    Compass-->>Camera: You are facing West
    Camera->>User: "The cup is directly behind you."
```

### 4. Emergency Fall Detection

```mermaid
graph TD
    A[Phone Moves Fast] --> B{Did it drop?}
    B -- Yes --> C[Fall Detected!]
    C --> D{Did it hit the ground?}
    D -- Yes --> E[Start Emergency]
    E --> F[Say: 'Fall Detected']
    E --> G[Send SOS Message]
    G --> H((Emergency Contacts))
```

---

## 🛠️ Technology Used

| Part | Tech |
| :--- | :--- |
| **Language** | Kotlin |
| **UI** | Jetpack Compose |
| **Offline AI** | TensorFlow Lite, Google ML Kit |
| **Online AI** | Nvidia / Meta LLaMA |
| **Database** | Room (SQLite) |

---

## 🚀 How to Run It

1. **Download** this code to your computer.
2. Open it in **Android Studio**.
3. Let it finish loading.
4. Create a `local.properties` file in the root directory (if it doesn't exist) and add your API keys:
   ```properties
   TELEGRAM_BOT_TOKEN=your_telegram_bot_token
   TELEGRAM_CHAT_ID=your_telegram_chat_id
   NVIDIA_API_KEY=your_nvidia_api_key
   NVIDIA_META_API_KEY=your_nvidia_meta_api_key
   ```
5. **Run** it on a real Android phone (the camera and sensors won't work properly on an emulator).

---

## 🗣️ Voice Commands to Try

*   🗣️ *"Describe what you see"*
*   🗣️ *"Find a chair"*
*   🗣️ *"Where is my cup?"*
*   🗣️ *"Read"*
*   🗣️ *"Turn off flashlight"*
*   🗣️ *"Switch to spatial"* (for 3D sound)
