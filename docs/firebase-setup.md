# Firebase Setup Guide for gentle connection

This document outlines the step-by-step procedures for the project owner to provision Firebase backend services, deploy security rules & indexes, and configure both the Android app and Caregiver Web Portal.

---

## 1. Create Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** (or **Create a project**).
3. Enter a project name (e.g., `gentle-connection-prod`).
4. (Optional) Disable Google Analytics or select your Analytics account.
5. Click **Create project** and wait for provisioning.

---

## 2. Enable & Provision Cloud Firestore

1. In the left navigation menu, go to **Build** → **Firestore Database**.
2. Click **Create database**.
3. Select your preferred database region (e.g., `europe-west1` or `nam5`).
4. Start in **Production mode** (security rules will be deployed from the repository).
5. Click **Create**.

---

## 3. Enable Authentication Providers

**Both** providers are required, and Anonymous now carries both ends. The phone signs in
anonymously so that it owns its own device document — if Anonymous is left disabled, the
phone silently fails to register and no pairing code will ever work. The caregiver portal
also signs in anonymously when somebody arrives on the link from her phone, which is what
lets that link open the settings with no login at all; with the provider off, the link
falls back to the Google button and says so.

Google stays for a caregiver who wants an identity that survives clearing their browser
storage, or who works from more than one device.

1. In the left navigation menu, go to **Build** → **Authentication**.
2. Click **Get started**.
3. Under the **Sign-in method** tab, click **Google** under *Additional providers*.
4. Enable the provider toggle, select the project support email, and click **Save**.
5. Back on **Sign-in method**, click **Anonymous** under *Additional providers*.
6. Enable the provider toggle and click **Save**.

---

## 4. Register the Android App

1. In the Project Overview page, click the **Android** icon (or go to **Project settings** → **Add app** → **Android**).
2. Enter the exact package name:
   `ch.heuscher.gentleconnect`
3. Enter an app nickname (e.g., `gentle connection Android`).
4. (Optional) Enter your SHA-1 fingerprint if testing Google Sign-In or App Check on device.
5. Click **Register app**.
6. Download `google-services.json`.
7. Move `google-services.json` into the `app/` folder of this repository:
   ```bash
   cp ~/Downloads/google-services.json app/google-services.json
   ```

---

## 5. Configure Caregiver Web Portal

1. In **Project settings** → **General**, scroll to *Your apps* and click **Add app** → **Web** (`</>`).
2. Register the web app with nickname `gentle connection Portal`.
3. Copy the generated `firebaseConfig` object.
4. Copy `caregiver-portal/config.example.js` to `caregiver-portal/config.js`:
   ```bash
   cp caregiver-portal/config.example.js caregiver-portal/config.js
   ```
5. Paste your project API keys and project IDs into `caregiver-portal/config.js`:
   ```javascript
   window.firebaseConfig = {
     apiKey: "AIzaSy...",
     authDomain: "YOUR_PROJECT_ID.firebaseapp.com",
     projectId: "YOUR_PROJECT_ID",
     storageBucket: "YOUR_PROJECT_ID.firebasestorage.app",
     messagingSenderId: "123456789012",
     appId: "1:123456789012:web:abcdef..."
   };
   ```

---

## 6. Deploy Security Rules & Indexes

Install Firebase CLI if not already installed (`npm install -g firebase-tools`).

1. Log in to Firebase:
   ```bash
   firebase login
   ```
2. Select your Firebase project:
   ```bash
   firebase use --add
   ```
3. Deploy Firestore rules and indexes:
   ```bash
   firebase deploy --only firestore:rules,firestore:indexes
   ```
4. Deploy Firebase Hosting (if hosting the caregiver portal on Firebase):
   ```bash
   firebase deploy --only hosting
   ```

---

## 7. Add Authorized Domains for Auth

If hosting the portal on a custom domain or external host (e.g., GitHub Pages, Vercel, Netlify):

1. Go to **Authentication** → **Settings** → **Authorized domains**.
2. Click **Add domain**.
3. Add your web portal domain (e.g., `caregiver.yourdomain.com`).

---

## 8. Bootstrapping a Device Owner (Slice 3a)

Management actions (policy configuration, remote commands, releases) require the **Owner** role (PLAN §8 decision 4). The owner role is distinct from an ordinary caregiver: it requires both an entry in `devices/<deviceId>/access/owners` and an authenticated Google identity (`google.com`).

### How to become an owner:
1. **Connect as caregiver:** Open the pairing link from her phone on the Caregiver Portal. This creates an anonymous caregiver session linked to the device.
2. **Link Google Account:** On the 🛡️ Gerät card, click **«Als Verwalter anmelden»**. This links the existing anonymous session UID to a Google account without losing the connection to the phone.
3. **Bootstrap owner entry:** The administrator runs `scripts/bootstrap-owner.sh` with Application Default Credentials:
   ```bash
   scripts/bootstrap-owner.sh <deviceId> <google-email>
   ```
   (Pass `--dry-run` to preview the lookup and Firestore update without writing).

Owner UIDs are stored in `devices/<deviceId>/access/owners`. Portal-side owner management (adding or removing owners directly in the portal) will come in a later slice.

