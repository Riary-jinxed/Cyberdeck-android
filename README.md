# Netrunner — launcher Cyberpunk 2077 pour Android

Un écran d'accueil Android inspiré de l'interface de Cyberpunk 2077 : jaune néon, cyan, glitchs, lignes de balayage CRT et police HUD. Pensé pour le Xiaomi 13 Pro (HyperOS, Android 13+), il marche sur tout Android 10+.

## Ce qu'il fait

- **HUD d'accueil** : grosse horloge jaune qui « glitche » de temps en temps, date, batterie segmentée, ligne de terminal animée.
- **Quickhacks** : tes applis favorites en tuiles angulaires (appui long dans le tiroir pour épingler).
- **Accès réseau** : tiroir de toutes les applis avec recherche (Entrée lance le premier résultat).
- **Lancement en ligne de commande** : chaque appli s'ouvre après une courte séquence « jack-in » façon terminal.
- **Terminal intégré** : `scan` (appareils du réseau Wi-Fi), `ports`, `ping`, `ip`, `sysinfo`, `battery`, `apps`, `open <appli>`.
- **Scanner Wi-Fi** : toutes les bornes à portée (SSID, BSSID, dBm, canal, bande, sécurité, norme Wi-Fi, distance estimée) et un mode « traque » avec radar, tendance et bip type compteur Geiger pour retrouver une borne physiquement.
- **Claude** : glisser vers la gauche ouvre l'appli Claude officielle, connectée à ton compte. Commande terminal : `claude`.
- **As//ka** : programme indépendant, une IA draconique noir et rose qui veille à côté de l'horloge. Touche son sigle (ou `aska` dans le terminal, ou un geste au choix) pour discuter. Elle pense **entièrement sur le téléphone** (LiteRT-LM de Google) : gratuit, privé, sans compte. Son noyau se télécharge une fois depuis son panneau (Gemma 4 E2B 2,6 Go, Qwen 2.5 1.5B 1,6 Go ou Qwen 3 0.6B 614 Mo, Wi-Fi par défaut) ou s'importe en `.litertlm`. Elle gère aussi les alarmes et minuteurs (« réveille-moi à 7h30 », « minuteur 10 min », « prochaine alarme ? »), toujours avec confirmation. Elle garde les 1000 derniers messages et une mémoire longue : « retiens que… », « qu'est-ce que tu sais sur moi ? », « oublie que… », « oublie tout ». Elle peut lire ses réponses à voix haute, hors ligne, avec une voix filtrée façon IA du jeu : packs SPECTRE et ORACLE (féminins), CHROME et CONSTRUCT (masculins) ou BRUTE (sans filtre). Le modèle n'est chargé que pendant la discussion (GPU si possible, sinon CPU) et libéré 90 s après la fermeture du panneau.
- **Holo-liaison (appels)** : Netrunner peut devenir l'appli Téléphone. Appels entrants et sortants façon holo-appel du jeu (portrait hologramme, décrocher/refuser, micro, haut-parleur, clavier DTMF, mise en attente, raccrocher), écran d'appel au-dessus de l'écran verrouillé, clavier, journal et contacts.
- **Clavier Netrunner** : clavier AZERTY néon (majuscules, verrouillage, chiffres et symboles, accents par appui long, effacement continu, flèches de curseur). À activer dans Paramètres › Clavier, ou avec la commande `keyboard`. Pavé numérique automatique pour les champs numéro et téléphone (touche 123 sinon), touche @ ou / pour les e-mails et adresses web, suggestions du correcteur orthographique d'Android et correction automatique (désactivable dans Config).
- **Flux entrant** : notifications (SMS, WhatsApp, mails, appels manqués) dans le HUD, avec ouverture, suppression et réponse rapide intégrée.
- **Widget musique** : Spotify (ou tout autre lecteur) avec pochette, titre, progression, lecture/pause/suivant.
- **Contrôle du deck** : le bouton « ◢ DECK » en haut à droite (ou un glisser vers le bas dans le quart haut droit, hors bord de l'écran) ouvre un panneau Wi-Fi, données, Bluetooth, lampe, sonnerie, Ne pas déranger, rotation et volumes.
- **Verrou cyber** : écran de verrouillage cyberdeck (horloge, batterie, RAM, musique, signaux entrants sans le contenu des messages). Glisser vers le haut passe la main au déverrouillage système (empreinte, visage ou code), la sécurité reste celle d'Android. À activer dans le panneau de contrôle (tuile « VERROU CYBER ») ou avec `lock on`.
- **Sobre en batterie** : toutes les animations et boucles s'arrêtent dès que l'écran est éteint ou qu'une autre appli est au premier plan ; la mascotte tourne à 15 images/s au lieu de 120 ; aucun service en arrière-plan en plus de l'accès aux notifications.
- **Factions** : thèmes NETRUNNER, ARASAKA, MILITECH et NETWATCH (panneau de contrôle ou `theme <faction>`), qui recolorent tout le deck.
- **Biomoniteur** : température, RAM, stockage et données affichés comme des implants.
- **Mode furtif** : double-tap sur l'horloge, tuile « MODE FURTIF » ou `ghost`. Silence (alarmes conservées), HUD GHOST sombre, puis panneau système pour couper les radios (Android interdit aux applis de couper Wi-Fi et Bluetooth elles-mêmes).
- **Conditions de zone et contrats** : météo Open-Meteo (sans compte, mise en cache 30 min) et prochains rendez-vous de l'agenda.
- **Sons d'interface** : bips et glitchs synthétisés + vibrations, coupés si le téléphone est en silencieux (`sound off` pour les désactiver).
- **Interface de piratage** : fond « breach protocol » (grille de codes hexadécimaux balayée par un curseur) ou pluie de code, effet cathodique (vignettage, bande défilante, scintillement) et glitchs ponctuels de l'écran. Le fond vire au rouge sous 20 % de batterie et accélère en charge. 10 à 12 images/s, à l'arrêt dès que l'écran n'est pas visible.
- **Terminal** : aussi `torch`, `timer`, `calc`, `qr`, `speedtest`, `ghost`, `theme`, `wallpaper`, `sound`.
- **Polices HUD** : Rajdhani pour l'interface, Orbitron pour l'horloge, Share Tech Mono pour le terminal (licence SIL OFL, voir `licenses/`).
- **Icônes néon** : icônes des applis recolorées à la couleur de la faction (désactivable).
- **Réseau (tiroir)** : catégories COMMS, MÉDIA, JEUX, NAV, OUTILS, onglet FRÉQUENTS trié par usage, et recherche universelle (applis, contacts, commandes ; préfixe `>` pour lancer une commande).
- **Config et diagnostic** : appui long sur l'horloge, bouton du panneau de contrôle ou `config`. Réglages du deck et vérification de chaque autorisation Android/HyperOS avec accès direct à la bonne page.
- **Messagerie SMS** : bouton « ✉ SMS » ou `sms`. Conversations façon messagerie du jeu (portraits, canaux, bulles angulaires), réponses rapides en options de dialogue et envoi par la SIM. L'appli SMS habituelle reste en place pour les MMS, le RCS et les notifications.
- **Animation de démarrage** : styles BIOS, BREACH (matrice qui se résout en « accès autorisé ») ou CORPO (logo qui glitche), ou aucune ; affiche ton pseudo de runner. Dans Config.
- **Fond d'écran système** : la grille de breach en fond d'écran animé Android, aussi sur l'écran verrouillé, aux couleurs de la faction et à 8 images/s.
- **Sonneries et sons** : 3 sonneries, 3 sons de notification et 1 alarme synthétisés sur le téléphone, à écouter puis installer par défaut en un appui (Config › Sonneries et sons).
- **Tuiles du volet** : mode furtif, verrou cyber, terminal et scan Wi-Fi dans les réglages rapides d'Android (ajout en un appui depuis Config).
- **Widgets Android** : deuxième page de l'accueil (glisser sur la zone centrale), n'importe quel widget (agenda, Maps, Keep…) dans un cadre HUD, à déplacer, agrandir ou retirer.
- **Dossiers et pages de quickhacks** : appui long sur un quickhack › « Nouveau dossier » ou « Ranger dans… » ; au-delà de 8 raccourcis, la grille défile en pages.
- **Gestes personnalisables** : chaque glisser et le double-tap / appui long sur l'horloge se règlent dans Config › Gestes.
- **Profils Boulot / Perso** : chacun garde ses quickhacks, dossiers, faction, fond, sons d'interface et mode de sonnerie.
- **Mode nuit Netwatch** : interface assombrie, sans fond animé ni glitchs, sur une plage horaire choisie.
- **Spectre audio** : barres de visualiseur dans le widget musique (vrai son avec l'accès micro, animation sinon).
- **Sauvegarde** : export et restauration de toute la config dans un fichier JSON.
- **Mise à jour intégrée** : Netrunner lit la dernière release GitHub, télécharge l'APK et lance l'installation. Chaque push sur `main` publie une release. Pour que les mises à jour s'installent par-dessus, ajoute les secrets `KEYSTORE_BASE64` et `KEYSTORE_PASSWORD` (clé de signature stable) au dépôt.
- **Kit de terrain** (bouton KIT ou `kit`) : inventaire matériel avec scan des codes-barres et QR (fiche avec numéro de série, emplacement, état, notes, recherche et export CSV), checklists d'intervention à partir de modèles personnalisables, et calculateur de sous-réseaux (aussi `subnet 192.168.1.0/24 /26`). Tout reste en local sur le téléphone.
- **Écran d'accueil par défaut** : un bandeau rouge s'affiche tant que Netrunner n'est pas l'écran d'accueil, un appui ouvre le bon réglage.
- **Gestes** : glisser vers le haut ouvre le tiroir, vers le bas ouvre les notifications, vers la droite ouvre le terminal, vers la gauche ouvre Claude.
- Mise à jour automatique de la liste quand tu installes ou supprimes une appli.

## Installer sur le téléphone

1. Récupère l'APK : onglet **Actions** du dépôt GitHub › dernier build › artefact `netrunner-launcher` (ou construis-le, voir plus bas).
2. Copie l'APK sur le téléphone et ouvre-le (autorise l'installation depuis le gestionnaire de fichiers).
3. Appuie sur le bouton Accueil, ou va dans Paramètres › Applications › Applications par défaut › **Écran d'accueil**, et choisis **Netrunner**.

Pour les appels : Dialer (bouton « APPELS ») › « Définir Netrunner comme appli Téléphone ». Pour revenir à l'appli Xiaomi : Paramètres › Applications › Applications par défaut › **Téléphone**.

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
| `aska/AskaBrain.kt` | IA locale As//ka : chargement du modèle, conversation, historique |
| `aska/AskaModels.kt` | Catalogue, téléchargement et import des modèles |
| `aska/AskaAlarms.kt` | Alarmes et minuteurs en langage naturel |
| `ui/AskaScreen.kt`, `ui/AskaSigil.kt` | Panneau de discussion et sigle draconique |
| `terminal/NetTools.kt` | Scan réseau, scan de ports, infos réseau |
| `system/SystemInfo.kt` | Batterie, mémoire, stockage, appareil |
| `system/HomeRole.kt` | Demande à devenir l'écran d'accueil |
| `wifi/WifiScanner.kt` | Scan des bornes Wi-Fi, estimation de distance |
| `ui/WifiScreen.kt` | Liste des bornes et mode traque |
| `system/ClaudeApp.kt` | Ouvre l'appli Claude |
| `ui/DeckChrome.kt` | Cadre HUD, barre RAM, séquence de démarrage |
| `ui/SettingsScreen.kt`, `system/Diagnostics.kt` | Réglages et diagnostic des autorisations |
| `ui/DeckSettings.kt` | Icônes néon, préférences d'affichage |
| `ui/LiveWallpaper.kt` | Fond breach / pluie de code, effet cathodique et glitchs |
| `lock/`, `ui/LockScreen.kt` | Verrou cyber |
| `call/` | Service d'appel (InCallService), notifications d'appel, contacts, journal |
| `ui/HoloCallScreen.kt`, `ui/DialerScreen.kt` | Écran d'holo-appel et clavier téléphonique |
| `keyboard/` | Clavier Netrunner (InputMethodService + Compose) |
| `notify/` | Service d'écoute des notifications et réponses rapides |
| `media/MediaWatcher.kt` | Suivi et contrôle du lecteur audio actif |
| `ui/FeedScreen.kt`, `ui/MediaWidget.kt`, `ui/QuickPanel.kt` | Flux, widget musique, réglages rapides |
| `sms/SmsStore.kt`, `ui/MessagesScreen.kt` | Lecture et envoi des SMS, messagerie |
| `system/Tones.kt` | Synthèse et installation des sonneries |
| `tiles/DeckTiles.kt` | Tuiles du volet de réglages rapides |
| `wallpaper/BreachWallpaperService.kt` | Fond d'écran animé système |

## Idées pour la suite

- Widgets Android (météo, musique) dans un cadre HUD
- Pack d'icônes monochromes jaune/cyan
