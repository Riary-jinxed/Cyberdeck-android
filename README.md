# Netrunner — launcher Cyberpunk 2077 pour Android

Un écran d'accueil Android inspiré de l'interface de Cyberpunk 2077 : jaune néon, cyan, glitchs, lignes de balayage CRT et police HUD. Pensé pour le Xiaomi 13 Pro (HyperOS, Android 13+), il marche sur tout Android 10+.

## Ce qu'il fait

- **HUD d'accueil** : grosse horloge jaune qui « glitche » de temps en temps, date, batterie segmentée, ligne de terminal animée.
- **Quickhacks** : tes applis favorites en tuiles angulaires (appui long dans le tiroir pour épingler).
- **Accès réseau** : tiroir de toutes les applis avec recherche (Entrée lance le premier résultat).
- **Lancement en ligne de commande** : chaque appli s'ouvre après une courte séquence « jack-in » façon terminal.
- **Terminal intégré** : `scan` (appareils du réseau Wi-Fi), `ports`, `ping`, `ip`, `sysinfo`, `battery`, `apps`, `open <appli>`.
- **Scanner Wi-Fi** : toutes les bornes à portée (SSID, BSSID, dBm, canal, bande, sécurité, norme Wi-Fi, distance estimée) et un mode « traque » avec radar, tendance et bip type compteur Geiger pour retrouver une borne physiquement.
- **GLITCH, l'IA du cyberdeck** : glisser vers la gauche ouvre un chat avec Claude (Claude Opus 5.5 via l'API Anthropic, clé API à fournir), avec une mascotte animée qui réagit pendant qu'elle réfléchit et répond.
- **Flux entrant** : notifications (SMS, WhatsApp, mails, appels manqués) dans le HUD, avec ouverture, suppression et réponse rapide intégrée.
- **Widget musique** : Spotify (ou tout autre lecteur) avec pochette, titre, progression, lecture/pause/suivant.
- **Contrôle du deck** : glisser vers le bas depuis le haut à droite ouvre un panneau Wi-Fi, données, Bluetooth, lampe, sonnerie, Ne pas déranger, rotation et volumes.
- **Écran d'accueil par défaut** : un bandeau rouge s'affiche tant que Netrunner n'est pas l'écran d'accueil, un appui ouvre le bon réglage.
- **Gestes** : glisser vers le haut ouvre le tiroir, vers le bas ouvre les notifications.
- Mise à jour automatique de la liste quand tu installes ou supprimes une appli.

## Installer sur le téléphone

1. Récupère l'APK : onglet **Actions** du dépôt GitHub › dernier build › artefact `netrunner-launcher` (ou construis-le, voir plus bas).
2. Copie l'APK sur le téléphone et ouvre-le (autorise l'installation depuis le gestionnaire de fichiers).
3. Appuie sur le bouton Accueil, ou va dans Paramètres › Applications › Applications par défaut › **Écran d'accueil**, et choisis **Netrunner**.

Sur HyperOS, les gestes plein écran fonctionnent avec un launcher tiers, mais l'animation des applis récentes est un peu plus simple qu'avec le launcher Xiaomi. Pour revenir en arrière, rechoisis « Écran d'accueil système » au même endroit.

## Construire soi-même

Avec Android Studio (Ladybug ou plus récent) : ouvre le dossier, laisse Gradle se synchroniser, puis **Run**. En ligne de commande, avec le SDK Android installé :

```bash
./gradlew assembleRelease
# APK : app/build/outputs/apk/release/app-release.apk
```

## Structure

| Fichier | Rôle |
| --- | --- |
| `MainActivity.kt` | Activité « HOME » déclarée comme écran d'accueil |
| `AppRepository.kt` | Liste des applis (LauncherApps), lancement, favoris |
| `LauncherViewModel.kt` | État de l'écran, suivi des installations |
| `ui/Theme.kt` | Palette Night City et police |
| `ui/Effects.kt` | Glitch, scanlines, grille, panneaux angulaires |
| `ui/HomeScreen.kt` | HUD, quickhacks, tiroir d'applis |
| `ui/BreachOverlay.kt` | Animation « jack-in » au lancement d'une appli |
| `ui/TerminalScreen.kt` | Interface du terminal |
| `terminal/Terminal.kt` | Interpréteur de commandes |
| `terminal/NetTools.kt` | Scan réseau, scan de ports, infos réseau |
| `system/SystemInfo.kt` | Batterie, mémoire, stockage, appareil |
| `system/HomeRole.kt` | Demande à devenir l'écran d'accueil |
| `wifi/WifiScanner.kt` | Scan des bornes Wi-Fi, estimation de distance |
| `ui/WifiScreen.kt` | Liste des bornes et mode traque |
| `chat/ChatSession.kt` | Conversation avec Claude (SDK Java Anthropic, streaming) |
| `ui/ChatScreen.kt` | Interface de chat GLITCH |
| `ui/Mascot.kt` | Mascotte GLITCH animée |
| `notify/` | Service d'écoute des notifications et réponses rapides |
| `media/MediaWatcher.kt` | Suivi et contrôle du lecteur audio actif |
| `ui/FeedScreen.kt`, `ui/MediaWidget.kt`, `ui/QuickPanel.kt` | Flux, widget musique, réglages rapides |

## Idées pour la suite

- Widgets Android (météo, musique) dans un cadre HUD
- Pack d'icônes monochromes jaune/cyan
- Police Rajdhani/Orbitron embarquée
- Fond d'écran animé (pluie néon)
