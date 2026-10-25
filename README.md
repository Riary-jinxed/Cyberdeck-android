# Netrunner — launcher Cyberpunk 2077 pour Android

Un écran d'accueil Android inspiré de l'interface de Cyberpunk 2077 : jaune néon, cyan, glitchs, lignes de balayage CRT et police HUD. Pensé pour le Xiaomi 13 Pro (HyperOS, Android 13+), il marche sur tout Android 10+.

## Ce qu'il fait

- **HUD d'accueil** : grosse horloge jaune qui « glitche » de temps en temps, date, batterie segmentée, ligne de terminal animée.
- **Quickhacks** : tes applis favorites en tuiles angulaires, en tête de la page Applis du deck (glisser vers la gauche) ; appui long dans le tiroir pour épingler.
- **Accès réseau** : tiroir de toutes les applis avec recherche (Entrée lance le premier résultat).
- **Lancement en ligne de commande** : chaque appli s'ouvre après une courte séquence « jack-in » façon terminal.
- **Terminal intégré** : `scan` (appareils du réseau Wi-Fi), `ports`, `ping`, `ip`, `sysinfo`, `battery`, `apps`, `open <appli>`.
- **Scanner Wi-Fi** : toutes les bornes à portée (SSID, BSSID, dBm, canal, bande, sécurité, norme Wi-Fi, distance estimée) et un mode « traque » avec radar, tendance et bip type compteur Geiger pour retrouver une borne physiquement.
- **Applis du deck** (glisser vers la gauche, ou `hub`) : une page avec tes quickhacks, les applis Netrunner installées (horloge, calculatrice, fichiers…), celles à installer ou à mettre à jour, et des raccourcis : Claude (l'appli officielle, connectée à ton compte), As//ka, Stats, Kit, Terminal, SMS. Commande terminal pour Claude : `claude`.
- **As//ka** : programme indépendant, une IA draconique noir et rose qui veille à côté de l'horloge. Touche son sigle (ou `aska` dans le terminal, ou un geste au choix) pour discuter. Elle pense **entièrement sur le téléphone** (LiteRT-LM de Google) : gratuit, privé, sans compte. Son noyau se télécharge une fois depuis son panneau (Gemma 4 E2B 2,6 Go, Qwen 2.5 1.5B 1,6 Go ou Qwen 3 0.6B 614 Mo, Wi-Fi par défaut) ou s'importe en `.litertlm`. Elle gère aussi les alarmes et minuteurs (« réveille-moi à 7h30 », « minuteur 10 min », « prochaine alarme ? »), toujours avec confirmation. Elle garde les 1000 derniers messages et une mémoire longue : « retiens que… », « qu'est-ce que tu sais sur moi ? », « oublie que… », « oublie tout ». Elle peut lire ses réponses à voix haute, hors ligne, avec une voix filtrée façon IA du jeu : packs SPECTRE et ORACLE (féminins), CHROME et CONSTRUCT (masculins) ou BRUTE (sans filtre). Le modèle n'est chargé que pendant la discussion (GPU si possible, sinon CPU) et libéré 90 s après la fermeture du panneau.
- **Holo-liaison (appels)** : Netrunner peut devenir l'appli Téléphone. Appels entrants et sortants façon holo-appel du jeu (portrait hologramme, décrocher/refuser, micro, haut-parleur, clavier DTMF, mise en attente, raccrocher), écran d'appel au-dessus de l'écran verrouillé, clavier, journal et contacts.
- **Clavier Netrunner** : clavier AZERTY néon (majuscules, verrouillage, chiffres et symboles, accents par appui long, effacement continu, flèches de curseur). À activer dans Paramètres › Clavier, ou avec la commande `keyboard`. Pavé numérique automatique pour les champs numéro et téléphone (touche 123 sinon), touche @ ou / pour les e-mails et adresses web, suggestions du correcteur orthographique d'Android et correction automatique (désactivable dans Config).
- **Flux entrant** : notifications (SMS, WhatsApp, mails, appels manqués) dans le HUD, avec ouverture, suppression et réponse rapide intégrée.
- **Widget musique** : Spotify (ou tout autre lecteur) avec pochette, titre, progression, lecture/pause/suivant.
- **Contrôle du deck** : le bouton « ◢ DECK » en haut à droite (ou un glisser vers le bas dans le quart haut droit, hors bord de l'écran) ouvre un panneau Wi-Fi, données, Bluetooth, lampe, sonnerie, Ne pas déranger, rotation et volumes.
- **Verrou cyber** : écran de verrouillage cyberdeck (horloge, batterie, RAM, musique, signaux entrants sans le contenu des messages). Glisser vers le haut passe la main au déverrouillage système (empreinte, visage ou code), la sécurité reste celle d'Android. L'option « Passer la main au visage » (ou `lock face`) le fait automatiquement à l'allumage, pour que le visage ou l'empreinte déverrouille sans toucher au code. À activer dans le panneau de contrôle (tuile « VERROU CYBER ») ou avec `lock on`.
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
- **Sonneries et sons** : 3 sonneries, 3 sons de notification et 1 alarme synthétisés sur le téléphone, à écouter puis installer par défaut en un appui (Config › Sonneries et sons). « Importer une musique » prend un morceau du téléphone : on choisit l'extrait (40 s max), on l'écoute, puis on le définit comme sonnerie ou notification. Le fichier découpé reste sur le téléphone, rien n'est envoyé ni inclus dans l'appli.
- **Tuiles du volet** : mode furtif, verrou cyber, terminal et scan Wi-Fi dans les réglages rapides d'Android (ajout en un appui depuis Config).
- **Widgets Android** : deuxième page de l'accueil (glisser sur la zone centrale), n'importe quel widget (agenda, Maps, Keep…) dans un cadre HUD, à déplacer, agrandir ou retirer.
- **Dossiers et pages de quickhacks** : appui long sur un quickhack › « Nouveau dossier » ou « Ranger dans… » ; la page défile quand il y a beaucoup de raccourcis.
- **Gestes personnalisables** : chaque glisser et le double-tap / appui long sur l'horloge se règlent dans Config › Gestes.
- **Stats du deck** : temps d'écran, déverrouillages, ouvertures et applis les plus utilisées (aujourd'hui ou sur 7 jours, en barres segmentées façon HUD), plus la batterie consommée et rechargée par jour. Il faut activer « Accès aux données d'utilisation ». Tout est calculé sur le téléphone, et l'historique batterie reste dans les fichiers privés de l'appli. On y voit aussi la data consommée (mobile et Wi-Fi, par jour et par appli). **Limites ICE** : touche une appli dans les stats pour lui fixer un temps max par jour. Une fois ce temps écoulé, un écran d'intrusion Netwatch la recouvre (bouton Déconnexion ou +5 min). Il faut « Afficher par-dessus les autres applis », sinon c'est une notification. À ouvrir avec `stats` dans le terminal ou via un geste.
- **Applis du deck** : Config › Applis du deck liste les autres applis Netrunner du dépôt (horloge, calculatrice, fichiers…) pour les installer, les mettre à jour ou les ouvrir. Une nouvelle appli y apparaît dès sa première release, si elle respecte la convention : tag `<id>-v<version>`, APK `<id>-<version>.apk`, `--latest=false`, et paquet `dev.netrunner.<id>` (sinon, l'ajouter à `DeckApps.PACKAGES`).
- **Mode éco Ripperdoc** : coupe le fond animé, les glitchs, l'animation de démarrage et le visualiseur, et fige le fond d'écran animé. En Auto, il s'active sous 20 % de batterie (hors charge) ou avec l'économiseur d'Android ; il peut aussi rester toujours actif (Config › Mode éco Ripperdoc). Le HUD affiche alors RIPPERDOC://ÉCO.
- **Profils Boulot / Perso** : chacun garde ses quickhacks, dossiers, faction, fond, sons d'interface et mode de sonnerie.
- **Mode nuit Netwatch** : interface assombrie, sans fond animé ni glitchs, sur une plage horaire choisie.
- **Spectre audio** : barres de visualiseur dans le widget musique (vrai son avec l'accès micro, animation sinon).
- **Sauvegarde** : export et restauration de toute la config dans un fichier JSON.
- **Mise à jour intégrée** : Netrunner lit la dernière release GitHub, télécharge l'APK et lance l'installation. Chaque push sur `main` publie une release. Pour que les mises à jour s'installent par-dessus, ajoute les secrets `KEYSTORE_BASE64` et `KEYSTORE_PASSWORD` (clé de signature stable) au dépôt.
- **Kit de terrain** (bouton KIT ou `kit`) : inventaire matériel avec scan des codes-barres et QR (fiche avec numéro de série, emplacement, état, notes, recherche et export CSV), checklists d'intervention à partir de modèles personnalisables, et calculateur de sous-réseaux (aussi `subnet 192.168.1.0/24 /26`). Tout reste en local sur le téléphone.
- **Écran d'accueil par défaut** : un bandeau rouge s'affiche tant que Netrunner n'est pas l'écran d'accueil, un appui ouvre le bon réglage.
- **Gestes** : glisser vers le haut ouvre le tiroir, vers le bas ouvre les notifications, vers la droite ouvre le terminal, vers la gauche ouvre les applis du deck.
- Mise à jour automatique de la liste quand tu installes ou supprimes une appli.

## Horloge Netrunner (appli à part)

Une deuxième appli, dans le module `clock/` (package `dev.netrunner.clock`, APK séparé signé avec la même clé), pour remplacer l'horloge Xiaomi dans le même style.

- **Horloge** plein écran et prochaine alarme.
- **Alarmes** : jours de répétition, libellé, vibreur, répit réglable, volume progressif, arrêt automatique.
- **Sonnerie au choix** : sonnerie du téléphone, sonnerie système, ou **musique importée** depuis le téléphone (MP3, M4A, OGG, FLAC…) avec l'extrait à jouer en boucle (début et fin). Le fichier est copié dans l'appli et ne quitte pas le téléphone.
- **Écran de réveil** plein écran par-dessus l'écran verrouillé : glisser pour arrêter, bouton ou touches de volume pour le répit. Après une alarme du matin, retour à l'accueil pour qu'As//ka dise bonjour (désactivable).
- **Minuteurs** (plusieurs à la fois, notification avec compte à rebours, pause, +1 min) et **chrono** avec tours.
- **Arrêt par QR code** (option par alarme) : on scanne d'avance un code de référence (QR collé dans la cuisine, code-barres du paquet de café…) ; l'alarme ne s'arrête qu'en le scannant à nouveau (déverrouillage demandé d'abord), sans bouton « Arrêter » dans la notification, et sonne jusqu'à une heure.
- **Fiable sur HyperOS** : vraies alarmes système (exactes, icône dans la barre d'état, reprogrammées au redémarrage et au changement d'heure) et une page CONFIG qui vérifie chaque réglage (notifications, plein écran, batterie, démarrage auto) avec accès direct.
- **Compatible As//ka** : l'appli répond aux intents Android standard de l'horloge, donc « réveille-moi à 7h30 », « minuteur 10 min » ou « prochaine alarme ? » marchent avec elle. La première fois, choisis Horloge Netrunner puis « Toujours ».
- **Sobre en batterie** : rien ne tourne en fond ; un service ne démarre que pendant qu'une alarme sonne.
- **Mises à jour** : releases GitHub `horloge-vX.Y.Z` (jamais marquées « latest », pour ne pas gêner la mise à jour du launcher), installables depuis CONFIG › Mise à jour.

## Calcul Netrunner (appli à part)

Une troisième appli, dans le module `calc/` (package `dev.netrunner.calc`, APK séparé signé avec la même clé). Elle partage avec les futures applis du deck le module `deck/` (factions, polices, widgets HUD, mises à jour). Toutes les applis du deck reprennent les 7 factions du launcher (couleurs, motif de fond, découpe des panneaux, glitch) et suivent automatiquement celle choisie dans le launcher, lue à chaque retour dans l'appli via son fournisseur `content://dev.netrunner.launcher.theme/current` (permission signature `dev.netrunner.permission.THEME`) ; CONFIG › Faction permet de garder un thème propre à une appli.

- **Scientifique** : priorités, parenthèses (fermées toutes seules), trigonométrie en degrés ou radians et fonctions inverses (touche 2ND), ln, log, eˣ, 10ˣ, puissances, racines, factorielle, pourcentage, mod, π, e, notation EE, ANS et multiplication implicite (`2π`, `3(4+1)`). Résultat en direct pendant la saisie, historique des 50 derniers calculs, appui long pour copier.
- **Programmeur** : entiers 8, 16, 32 ou 64 bits, signés ou non, saisie en HEX, DEC, OCT ou BIN avec les quatre bases affichées en même temps, AND, OR, XOR, NOT, NAND, NOR, décalages, MOD, et grille de bits à toucher pour les inverser.
- **Réseau** : calcul de sous-réseau IPv4 (masque, wildcard, réseau, broadcast, plage d'hôtes, type d'adresse), vue binaire, découpage en sous-réseaux et préfixe pour un nombre d'hôtes. Tout est calculé sur le téléphone.
- **Conversion** : longueur, masse, température, volume, surface, vitesse, données, temps, énergie, pression et angle, plus les devises (taux de la BCE, gardés hors connexion). La valeur s'affiche dans toutes les unités à la fois et peut être un calcul (`12*3`).
- **Raccourci calculatrice** : l'appli répond au raccourci « calculatrice » d'Android.
- **Mises à jour** : releases GitHub `calc-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour.

## Fichiers Netrunner (appli à part)

Gestionnaire de fichiers dans le module `files/` (package `dev.netrunner.files`, même clé de signature).

- **Explorateur façon terminal** : invite `runner@deck:~/Download$`, raccourcis (interne, Download, DCIM, images, documents, musique, vidéos, carte SD ou clé USB), dossiers dépliables sur place avec les traits de `tree`, couleurs par type de fichier, tri par nom, date, taille ou type, fichiers cachés au choix.
- **Actions** : appui long pour sélectionner, puis copier, couper et coller, renommer, supprimer (avec confirmation), infos (taille d'un dossier, droits), nouveau dossier, ouverture avec l'appli adaptée.
- **Aperçu intégré** des images, des fichiers texte et du contenu des zip, avec « Ouvrir avec » et partage.
- **Zip** : compresser la sélection en archive, extraire une archive dans un nouveau dossier (les chemins qui sortent du dossier sont refusés).
- **Partage** d'un ou plusieurs fichiers vers n'importe quelle appli.
- **Recherche** par nom (sans tenir compte des accents ni des majuscules) sous le dossier ouvert, résultats au fil de l'eau.
- Demande l'autorisation Android « Accès à tous les fichiers ». Rien n'est envoyé sur le réseau.
- **Corbeille** : SUPPR. envoie à la corbeille (bouton CORBEILLE de l'explorateur) pour restaurer à l'emplacement d'origine ; vidée automatiquement après 30 jours, ou DÉFINITIF pour effacer tout de suite.
- **Réseau (NAS)** : partages SMB2/3 de ton NAS (Synology, QNAP, Freebox, Windows, Samba) : parcourir, ouvrir, télécharger dans le dossier de l'explorateur, envoyer le presse-papiers, créer, renommer, supprimer. Mot de passe chiffré par le Keystore Android.
- **Mises à jour** : releases GitHub `files-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour.

## Agenda Netrunner (appli à part)

Agenda dans le module `agenda/` (package `dev.netrunner.agenda`, même clé de signature), branché sur le calendrier d'Android : tout reste synchronisé avec Google Agenda.

- **Contrats** : les 60 prochains jours, jour par jour, en cartes de mission (horaire, agenda d'origine, lieu, compte à rebours « DANS 2 H », « EN COURS »), aux couleurs de chaque agenda.
- **Mois** : grille du mois avec un point par contrat, et la liste du jour choisi.
- **Signer un contrat** : titre, journée entière ou horaires, lieu, briefing, rappel (5 min à 1 jour) et agenda de destination. Modification et suppression, bouton « Naviguer » vers Maps. Les événements récurrents s'affichent en lecture seule.
- **Rappels d'As//ka** : affichés en rose parmi les contrats, lus depuis le launcher (permission de signature `dev.netrunner.permission.READ_REMINDERS`).
- **Compatible** : ouvre les contrats du HUD du launcher, et répond aux intents Android standard de création d'événement (`ACTION_INSERT`), donc As//ka ou une autre appli peuvent pré-remplir un contrat.
- **Mises à jour** : releases GitHub `agenda-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Galerie Netrunner (appli à part)

Galerie dans le module `gallery/` (package `dev.netrunner.gallery`, même clé de signature), qui lit les photos et vidéos locales du téléphone.

- **Photos** : grille de toutes les images et vidéos, des plus récentes aux plus anciennes, de 2 à 5 colonnes, durée affichée sur les vidéos.
- **Albums** : un dossier par album (Appareil photo, Captures, WhatsApp…), avec sa vignette et son nombre d'éléments.
- **Visionneuse** : défilement de photo en photo, zoom au pincement, lecture des vidéos sur place avec barre de lecture, pause, ±10 s et vitesse (ou dans une autre appli), partage, ouverture dans l'éditeur du téléphone, infos (taille, date, dossier) et suppression avec la confirmation d'Android.
- **Filtre holo** : teinte de la faction, décalage RGB, scanlines et vignettage, sur la grille et dans la visionneuse. « Export holo » enregistre une copie filtrée dans Pictures/Netrunner.
- **Sélection multiple** : appui long sur une vignette, puis partager, mettre en favori ou supprimer plusieurs photos d'un coup.
- **Favoris** ★ depuis la sélection ou la visionneuse, avec un filtre « FAVORIS ».
- **Aller à une date** : bouton DATE pour sauter à un mois précis.
- **Accès partiel** : fonctionne aussi si seules certaines photos sont autorisées (Android 14+).
- **Mises à jour** : releases GitHub `gallery-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Notes Netrunner (appli à part)

Bloc-notes dans le module `notes/` (package `dev.netrunner.notes`, même clé de signature), au style terminal.

- **Notes et listes** : texte libre ou liste à cocher (les cases cochées descendent), passage de l'un à l'autre sans perte, titre, couleur néon par note.
- **Épinglage** : appui long sur une carte pour l'épingler en haut ; les autres sont triées par dernière modification.
- **Recherche** dans les titres et le contenu, sans tenir compte des accents ni des majuscules.
- **Partage** : une note se partage en texte, et du texte partagé depuis n'importe quelle appli devient une nouvelle note.
- **Export PDF** : une note depuis son bouton PDF, ou toutes les notes depuis CONFIG.
- **Kit de terrain** : LIER AU KIT ajoute un lien `kit:CODE`, et le bouton ◆ KIT ouvre la fiche dans le launcher (action `dev.netrunner.action.KIT_OPEN`, permission de signature `dev.netrunner.permission.KIT`).
- **As//ka** : fournisseur `content://dev.netrunner.notes.provider/notes` (permissions de signature `dev.netrunner.permission.READ_NOTES` et `WRITE_NOTES`) pour lire, chercher (`?q=mots`, `?kit=CODE`), ajouter, modifier (update sur `…/notes/ID` : `title`, `content`, `pinned`) et supprimer des notes ; colonnes `_id`, `title`, `content`, `checklist`, `pinned`, `updated`, `source`. Un contenu en lignes `[ ] …` / `[x] …` crée une liste. Un intent VIEW sur `…/notes/ID` ouvre la note.
- **Mises à jour** : releases GitHub `notes-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Dictaphone Netrunner (appli à part)

Enregistreur vocal dans le module `recorder/` (package `dev.netrunner.recorder`, même clé de signature), au style terminal.

- **REC** : enregistrement AAC (.m4a) avec forme d'onde en direct, chrono, pause et reprise.
- **Écran éteint** : l'enregistrement continue en arrière-plan avec une notification (bouton ARRÊTER).
- **Archives** : lecteur avec forme d'onde (touche pour se déplacer), ±10 s et vitesse de lecture ; partager, renommer, supprimer.
- **Qualité** : réglage haute qualité (48 kHz / 192 kb/s) ou économe (44,1 kHz / 96 kb/s) dans CONFIG.
- **Transcription** : TRANSCRIRE EN TEXTE convertit un enregistrement en texte sur le téléphone (Vosk, hors ligne, modèle français d'environ 41 Mo à télécharger une fois dans CONFIG). Le texte se copie ou part dans l'appli Notes (→ NOTES), automatiquement si « Ranger dans Notes » est activé.
- **Mises à jour** : releases GitHub `recorder-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Scanner Netrunner (appli à part)

Lecteur de QR codes et codes-barres dans le module `scanner/` (package `dev.netrunner.scanner`, même clé de signature), au style terminal.

- **Scan** : caméra en direct avec réticule, lampe, et lecture depuis une image de la galerie (ML Kit, hors ligne).
- **Décodage** : liens, Wi-Fi (connexion en un geste), téléphone, SMS, e-mail, position GPS, carte de visite, code produit, texte.
- **Actions** : ouvrir, se connecter, appeler, copier, partager ; ouverture automatique des liens en option.
- **Historique** des 100 derniers scans, vibration réglable.
- **Kit de terrain** : + KIT envoie le code à l'inventaire du launcher (fiche existante ou nouvelle fiche pré-remplie) ; le mode inventaire le fait à chaque scan.
- **Mises à jour** : releases GitHub `scanner-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Météo Netrunner (appli à part)

Prévisions dans le module `weather/` (package `dev.netrunner.weather`, même clé de signature), au style terminal, données Open-Meteo (sans clé ni compte).

- **Rapport de mission** : conditions actuelles (température, ressenti, vent, humidité, pression), conseil du jour.
- **24 h** en courbe de température avec risque de pluie, et **7 jours** en barres min/max.
- **Lieux** : position du téléphone (localisation approximative) ou villes recherchées et enregistrées.
- **Satellite** : images Meteosat Third Generation (EUMETSAT, gratuit) autour du lieu, en couleurs, infrarouge ou brouillard, à trois échelles (local, région, Europe), animées sur les 3,5 dernières heures.
- **Agricole** : température et humidité du sol par profondeur, déficit de vapeur, bilan pluie − évapotranspiration sur 7 jours passés et à venir, degrés-jours, risque de gel et fenêtres de traitement (vent < 19 km/h, sans pluie).
- **As//ka** : son chibi change selon le temps (soleil, nuit, nuages, brouillard, pluie, orage, neige, canicule, grand froid, vent fort), avec une petite réplique ; désactivable dans CONFIG.
- **Hors ligne** : la dernière prévision reste affichée.
- **Mises à jour** : releases GitHub `weather-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Musique Netrunner (appli à part)

Lecteur de musique dans le module `music/` (package `dev.netrunner.music`, même clé de signature), au style terminal, sur Media3 (ExoPlayer).

- **Bibliothèque** : titres, albums (avec pochettes) et artistes de l'index musical d'Android, recherche sans accents ; appui long pour « Lire ensuite » ou « Ajouter à la file ».
- **Lecture** : pochette, barre de progression, aléatoire, répétition (file ou titre), file d'attente modifiable.
- **Visualiseur néon** : spectre calculé sur le son décodé (pas besoin du micro), en barres, onde ou anneau, aux couleurs de la faction ; halo désactivable dans CONFIG.
- **Arrière-plan** : la musique continue écran éteint, avec notification, écran de verrouillage, boutons du casque et pause quand le casque se débranche ; le widget musique du launcher la pilote aussi.
- **Mises à jour** : releases GitHub `music-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## OSINT Netrunner (appli à part)

Boîte à outils de recherche en sources ouvertes dans le module `osint/` (package `dev.netrunner.osint`, même clé de signature), au style terminal. Tout est passif : registres et archives publics, sans compte ni clé, jamais de scan de la cible.

- **Cible** : un seul champ pour un domaine, une IP ou un lien (ou « Partager » depuis une autre appli), puis les modules :
  - **WHOIS** (RDAP) : registrar, dates de création et d'expiration, statut, serveurs DNS, DNSSEC ;
  - **DNS** (DNS over HTTPS) : A, AAAA, CNAME, MX, NS, TXT, SOA, CAA, avec contrôle SPF et DMARC ; PTR pour une IP ;
  - **Certificats** (crt.sh) : sous-domaines vus dans Certificate Transparency, émetteurs ;
  - **IP / ASN** : opérateur, ASN, plage et titulaire du bloc, contact abus, localisation approximative ;
  - **Web** : chaîne de redirections, serveur, en-têtes de sécurité, cookies ;
  - **TLS** : protocole, émetteur, validité, noms couverts, clé (même pour un certificat refusé, pour voir pourquoi) ;
  - **Archives** : première et dernière capture de la Wayback Machine, années couvertes ;
  - **Pivots** : liens vers VirusTotal, urlscan, Shodan, SecurityTrails, AbuseIPDB…
- **Fichier** : métadonnées EXIF d'une photo (appareil, date, GPS avec carte, auteur), copie nettoyée sans métadonnées (JPEG et PNG sans perte, orientation conservée), recherche inversée (partage vers Google Lens ou liens Google, Bing, Yandex, TinEye pour une image en ligne), empreintes MD5/SHA-1/SHA-256 avec lien VirusTotal.
- **Carnet d'enquête** : « + CARNET » sur un résultat le garde avec la date, notes libres, export vers Notes Netrunner ou partage en texte.
- **Hors périmètre, volontairement** : recherche de personnes (nom, téléphone, e-mail) et scan actif de machines.
- **Mises à jour** : releases GitHub `osint-vX.Y.Z` (jamais marquées « latest »), installables depuis CONFIG › Mise à jour ou depuis Config › Applis du deck du launcher.

## Deck Link (appli PC, Windows et Linux)

Une appli pour le PC, dans `desktop/` (Kotlin, Compose Desktop), synchronisée avec le **kit de terrain** du launcher : inventaire, consommables, carnet d'interventions avec ses photos, clients, câbles, adressage, baies et relevés.

- **Appairage** : Deck Link affiche un QR code ; sur le téléphone, KIT › OUTILS › PC › « Scanner le QR du PC » (ou saisie du code à la main).
- **Synchro dans les deux sens** : à chaque fermeture du kit (désactivable) ou avec « Synchroniser maintenant ». Le téléphone applique d'abord les modifs faites sur le PC, puis envoie les fichiers qui ont changé. Pour un même élément, la modif la plus récente gagne. Le téléphone trouve le PC tout seul sur le Wi-Fi (diffusion UDP sur le port 47321), ou passe par le câble USB avec `adb reverse tcp:47321 tcp:47321`.
- **Modifier sur le PC** : créer, modifier et supprimer équipements, consommables, clients (hors contrats et devis) et câbles. Les modifs attendent dans `config/outbox.json` jusqu'à la synchro suivante. Le carnet, les photos, contrats et devis se modifient sur le téléphone.
- **Parc (Deck Probe)** : un agent PowerShell pour les PC Windows (et un agent Python pour les PC Linux, `install-linux.sh` dans le même pack) envoie leurs infos (nom, fabricant, modèle, n° de série, OS, CPU, RAM, disques, cartes réseau, domaine, utilisateur) à Deck Link, qui crée ou met à jour leur fiche d'inventaire (retrouvée par n° de série, sinon par nom ; les notes ajoutées à la main sont gardées). PARC › « Préparer le déploiement » écrit un dossier avec l'agent, sa config, `install.cmd` (en administrateur, ou en script de démarrage GPO : copie dans `C:\ProgramData\DeckProbe` et deux tâches planifiées, au démarrage et chaque jour) et un LISEZMOI. Les rapports sont signés (HMAC-SHA256) avec la clé du parc et reçus sur le port TCP 47322.
- **Logiciels et mises à jour** : l'agent relève aussi les logiciels installés, l'état de Windows Update (dernière mise à jour installée, mises à jour en attente, redémarrage requis) et l'espace libre des disques. PARC › un PC affiche tout ça, avec une recherche dans les logiciels. « Ajouter ce PC à l'inventaire » fait de même pour le PC de Deck Link, sans agent.
- **Alertes** : disque à moins de 10 Go ou 10 % libres, PC sans rapport depuis 14 jours, mises à jour en attente ou absentes depuis 45 jours, garantie qui finit sous 60 jours (champ « fin de garantie » des fiches, sur le PC comme sur le téléphone), consommable sous son seuil. L'écran ALERTES les liste ; chaque nouvelle alerte apparaît une fois dans la zone de notification.
- **Exports** : EXPORTS › classeur Excel avec une feuille par table (inventaire, consommables, clients, contrats, devis, câbles, interventions, parc, logiciels). CARNET › une intervention › « Rapport PDF » (client, dates, travaux, déroulé, matériel posé, photos, cadres de signature). INVENTAIRE › « Étiquettes QR » : planche A4 de 3 × 8 étiquettes 70 × 37 mm pour les équipements affichés, que le scanner de l'inventaire du téléphone reconnaît. Tout est rangé dans `<dossier>/exports`.
- **Sauvegardes datées** : après chaque synchro qui apporte du nouveau, Deck Link garde une copie de tout le kit dans `<dossier>/sauvegardes` (les 30 plus récentes), au format des sauvegardes du téléphone. Sur le téléphone, KIT › OUTILS › PC › « Restaurer une sauvegarde du PC » liste ces copies et en restaure une (une synchro part juste avant, pour garder aussi le kit actuel).
- **Sécurité** : la synchro est chiffrée (AES-256-GCM) avec le code d'appairage, chaque connexion a son propre défi, rien ne passe par Internet. « Nouveau code » sur le PC révoque l'ancien téléphone, « Nouvelle clé du parc » les anciens agents.
- **Corbeille** : un fichier supprimé sur le téléphone part dans `sync/kit/.corbeille/` sur le PC, jamais effacé.
- **Dossier** : tout est dans le dossier d'installation choisi à l'installation (`<dossier>/sync/kit`, `<dossier>/sync/parc`, `<dossier>/config`, `<dossier>/deploiement`, `<dossier>/exports`, `<dossier>/sauvegardes`). Installation par utilisateur (pas besoin d'être admin), pour que ce dossier reste modifiable. Si le dossier n'est pas modifiable (ex. `/opt` sous Linux), tout va dans `~/DeckLink`.
- **Arrière-plan** : fermer la fenêtre laisse Deck Link dans la zone de notification, prêt à recevoir ; « Quitter » l'arrête.
- **Installer** : release `decklink-v<version>` du dépôt, `.msi` pour Windows, `.deb` pour Linux. Au premier lancement, Windows demande d'autoriser Deck Link sur les réseaux privés : il faut accepter.
- Le protocole de synchro (`sync-core/`) est partagé entre le launcher et Deck Link, et testé (`./gradlew -p desktop test`).

## Netrunner OS (système Linux pour le parc)

Un système Linux prêt à installer, dans `os/`, construit avec Debian live-build sur **Debian 13** : l'ISO démarre en live depuis une clé USB, en français, puis s'installe sur le disque avec l'icône « Install Debian » (Calamares). Trois éditions sont prévues : **User**, **Portable** et **Admin**. La première est l'édition User.

- **Édition User** (utilisateur lambda), bureau XFCE : LibreOffice (Writer, Calc, Impress, pour les fichiers Word, Excel et PowerPoint), Firefox, **Vidéo** et **Photos** (applis maison), Kdenlive pour monter des vidéos, **PDF** (appli maison), impression (CUPS, pilotes HP et Gutenprint), Wi-Fi et Bluetooth, mises à jour de sécurité automatiques. Chaque type de fichier s'ouvre avec la bonne appli.
- **Applis maison** (`os-apps/`, Kotlin et Compose Desktop, au style du deck) : elles remplacent une à une les logiciels de l'édition User, pour que tout soit fait main et qu'As//ka puisse y entrer. Déjà là : **Photos** (galerie d'un dossier avec ses sous-dossiers, photo en grand avec zoom à la molette, déplacement, rotation, orientation EXIF des téléphones, diaporama, plein écran, corbeille ; flèches, + −, R, F, Espace, Suppr) et **Vidéo** (lecteur dessiné par l'appli, décodage par libVLC : barre de lecture, ± 10 s, volume à la molette, sous-titres et pistes audio, plein écran, le reste du dossier ensuite ; Espace, flèches, N/P, S, A, F) et **PDF** (pages en continu, zoom, recherche sans accents avec la liste des pages trouvées ; As//ka peut le lire pour le résumer ou répondre) et **Texte** (traitement de texte : titres, listes à puces et numérotées, citations, gras, italique, souligné, barré, alignements, rechercher et remplacer, annuler ; ouvre et enregistre les .docx de Word, les .odt, le texte et le Markdown ; As//ka lit le document, écrit dedans en Markdown, retravaille la sélection). LibreOffice reste installé le temps que Tableur et Présentation arrivent, et pour les vieux .doc. À venir : Tableur, Présentation, Web, Montage.
- **Pont As//ka** (`os-bridge/`) : chaque appli maison ouverte écoute sur un socket Unix (dans `$XDG_RUNTIME_DIR/netrunner-bridge`, accessible à l'utilisateur seul). As//ka y lit ce qui est à l'écran (« la photo IMG_0042.jpg, 3 sur 58, du dossier Vacances ») et les actions possibles (`photos_show`, `photos_next`, `photos_slideshow`, `photos_trash` avec confirmation…), puis les lance. Une nouvelle appli n'a qu'à décrire son contexte et ses actions.
- **Inventaire** : l'agent Deck Probe pour Linux est déjà installé. Dans le menu, « Inscrire ce PC à l'inventaire » demande le `deck-probe.json` du pack de déploiement de Deck Link (sur une clé USB par exemple) et le mot de passe administrateur, puis l'inventaire part au démarrage et chaque jour à 12:00.
- **As//ka en option** : son paquet est sur l'image, prêt à installer sans réseau depuis « Installer As//ka » dans le menu.
- **Télécharger** : release `os-user-v<version>` du dépôt (ou artefact `netrunner-os-user` du dernier build). Si l'ISO dépasse 2 Go, elle est en morceaux : `cat netrunner-os-user-*.iso.part* > netrunner-os-user.iso`, puis `sha256sum -c` avec le fichier `.sha256`. À écrire sur une clé USB avec Rufus, balenaEtcher ou `dd`.
- **Session live** : utilisateur `netrunner`, mot de passe `live`.
- **Construire soi-même** (Debian 13, en root, ou dans un conteneur `debian:trixie` en `--privileged`) : `sh os/deck-probe/build-deb.sh pkgs`, `./gradlew -p os-apps packageUberJarForCurrentOS && sh os/apps/build-deb.sh os-apps/build/compose/jars/*.jar pkgs`, puis `ASKA_DEB=aska.deb os/user/build.sh out pkgs/*.deb` (ASKA_DEB facultatif). La liste des logiciels est dans `os/user/config/package-lists/user.list.chroot`.

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
| `system/MusicTone.kt` | Découpe d'une musique importée en sonnerie |
| `system/Ripperdoc.kt` | Mode éco (animations coupées) |
| `system/UsageReport.kt`, `system/BatteryLog.kt`, `system/DataUsage.kt`, `ui/StatsScreen.kt` | Statistiques d'usage, de data et de batterie |
| `system/Ice.kt`, `ui/IceActivity.kt` | Limites ICE par appli |
| `tiles/DeckTiles.kt` | Tuiles du volet de réglages rapides |
| `wallpaper/BreachWallpaperService.kt` | Fond d'écran animé système |

## Idées pour la suite

- Widgets Android (météo, musique) dans un cadre HUD
- Pack d'icônes monochromes jaune/cyan
