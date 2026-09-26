# Kartland GNSS Logger (Android)

Application Android (Kotlin) qui pilote une puce GNSS externe (type u-blox NEO-M9N en dongle
USB, ~80€) connectée en USB-C au téléphone, pour dépasser le plafond de cadence 1Hz du GNSS
interne mesuré à Kartland (essai du 13/09/2026 : 312-332ms RMS vs Apex Timing, cadence
identifiée comme facteur limitant secondaire).

Objectif de cette première version : **valider que tous les champs nécessaires remontent
correctement** (vitesse Doppler notamment) avant tout raffinement — sans avoir besoin d'aller
sur un circuit. Pas de logique de porte/chronométrage ici : c'est un logger brut, dans le même
esprit que les logs GNSS déjà analysés par le pipeline Python existant.

## Câblage

1. Puce GNSS USB (ex. GNSS Store ELT0103 / NEO-M9N, connecteur SMA, antenne active à visser).
2. Câble USB-C OTG (le dongle expose un port USB-A ou USB-C selon le modèle — prévoir
   l'adaptateur correspondant côté téléphone).
3. Google Pixel 8, connecté en USB Host.
4. Comme lors de l'essai Kartland du 13/09 : l'ensemble (puce + antenne + câble) se glisse
   **flottant** (non fixé) dans la poche poitrine de la veste Soft Shell — même position que le
   Pixel 8 / Sensor Logger lors du test précédent, pour rester comparable.

Le téléphone doit rester en USB Host actif (pas de charge simultanée via le même port sauf si
le câble/adaptateur le permet — un adaptateur OTG "Y" avec alimentation externe évite de vider
la batterie sur une session longue).

## Build

Projet Gradle standard, à ouvrir dans Android Studio (Koala ou plus récent recommandé) :

- `compileSdk 34`, `minSdk 26`, `targetSdk 34`.
- Dépendance clé : `com.github.mik3y:usb-serial-for-android:3.10.0`, résolue via JitPack
  (déclaré dans `settings.gradle`).
- **Le jar du Gradle Wrapper n'est pas inclus** dans ce zip (fichier binaire). À l'ouverture,
  Android Studio proposera de le régénérer automatiquement ("Gradle wrapper is missing... "
  → accepter) ; sinon lancer `gradle wrapper` une fois si Gradle est installé en local.
- Première synchro Gradle : nécessite un accès réseau vers `jitpack.io`, `google()` et
  `mavenCentral()`.

Aucune icône de lanceur "designée" — un simple glyphe vectoriel (pion GNSS / pneu) suffit pour
un outil de dev.

## Débit série : détecté automatiquement

Rien à régler à la main. Comme on ne connaît pas les réglages d'usine de la carte, l'appli
**balaie les vitesses candidates** (38400, 9600, 115200, 57600, 230400, 4800) et s'arrête à la
première qui produit des données qui *parsent réellement* — phrases NMEA à checksum valide, ou
trames UBX à checksum valide. À la mauvaise vitesse, on ne reçoit que du bruit de trame, qui
échoue aux deux tests : le discriminant est fiable (vérifié sur 800 000 octets de bruit
aléatoire, zéro faux positif).

Ensuite, **l'appli élève le débit du module si nécessaire**, et ce n'est pas cosmétique :

| Baud | Cadence NAV-PVT théorique | Cadence utile (marge 40%) | 25Hz ? |
|---|---|---|---|
| 9600 | 9,6 Hz | 5,8 Hz | non |
| 38400 | 38,4 Hz | 23,0 Hz | non (limite) |
| 57600 | 57,6 Hz | 34,6 Hz | oui |
| 115200 | 115,2 Hz | 69,1 Hz | oui |

Une trame NAV-PVT fait 100 octets sur le fil ; à 25Hz cela représente 2500 octets/s, soit
~25 kbit/s de charge utile. Aux défauts d'usine u-blox habituels (9600, parfois 38400), le lien
**ne peut physiquement pas** transporter 25Hz. L'appli envoie donc un `UBX-CFG-PRT` pour faire
passer le récepteur à 115200, puis suit elle-même à cette vitesse et vérifie que le lien répond
toujours. Si le module ne suit pas (carte qui ignore CFG-PRT), elle revient à la vitesse d'origine
et le signale — on logge alors plus lentement plutôt que de ne rien logger.

Autre point non cosmétique : les phrases NMEA par défaut sont **coupées** (`CFG-MSG` à 0 sur
GGA/GLL/GSA/GSV/RMC/VTG) avant de monter en cadence. Laissées actives, ce trafic texte se
dispute la même bande passante UART que le binaire — GSV seul peut faire plusieurs centaines
d'octets par époque — et étoufferait le flux NAV-PVT.

## Identifier l'adaptateur (VID/PID)

`app/src/main/res/xml/device_filter.xml` liste les VID/PID des puces USB-série courantes
(CP210x, FTDI, PL2303, CH340, u-blox natif) pour permettre le lancement automatique de l'app au
branchement. Cette liste ne bloque pas la détection à l'exécution (`UsbSerialProber` fait sa
propre vérification de compatibilité), seulement l'auto-lancement.

Pas besoin d'aller chercher le VID/PID ailleurs : **l'appli l'affiche elle-même** sous le statut
de connexion, au format `CP21xx — VID 0x10C4 / PID 0xEA60`. Il suffit de recopier ces deux
valeurs dans `device_filter.xml` si elles n'y sont pas déjà (les valeurs y sont en décimal, la
conversion est immédiate en Python : `int("0x10C4", 16)`).

## Protocole UBX implémenté

- **Trame** : `0xB5 0x62` + classe(1) + id(1) + longueur LE16 + payload + checksum Fletcher-8
  (`UbxProtocol.kt`).
- **Au démarrage**, l'app envoie, dans cet ordre délibéré :
  - `UBX-CFG-PRT` (0x06 0x00, 20 octets) **si nécessaire** : élève le débit UART du récepteur à
    115200 quand sa vitesse d'usine est trop lente pour la cadence visée.
  - `UBX-CFG-MSG` (0x06 0x01) à 0 sur les phrases NMEA (classe 0xF0 : GGA, GLL, GSA, GSV, RMC,
    VTG) : libère la bande passante avant de monter en cadence.
  - `UBX-CFG-MSG` : active `UBX-NAV-PVT` (0x01 0x07), un message par solution.
  - `UBX-CFG-RATE` (0x06 0x08) : `measRate=40ms` (25Hz visé), `navRate=1`, `timeRef=UTC`.
- **Réception** : `UbxFrameParser.kt` est une machine à états alimentée octet par octet (les
  lectures USB ne respectent pas les limites de trames), qui valide le checksum et rejette les
  trames corrompues sans se bloquer.
- **NAV-PVT** (payload 92 octets, récepteurs M8+) : tous les champs listés dans la spec projet
  (iTOW, date/heure UTC, fixType, flags, numSV, lon/lat, height/hMSL, hAcc/vAcc, velN/E/D,
  gSpeed = vitesse Doppler, headMot, sAcc = précision vitesse Doppler, headAcc, pDOP) sont
  décodés dans `UbxProtocol.parseNavPvt()`.

Non implémenté volontairement à ce stade (pas nécessaire pour valider les champs de base) :
`UBX-CFG-NAV5` (dynamic model — un profil "automotive" ou "race" serait pertinent une fois passé
sur circuit, pour éviter que le filtre interne du récepteur ne lisse excessivement les
accélérations/freinages d'un kart), et `UBX-CFG-VALSET` (interface par clés 32 bits, plus
moderne mais volontairement évitée pour limiter les constantes non vérifiées).

## Procédure de test balcon / rue

But : confirmer que la vitesse Doppler (`gSpeed`/`sAcc`) et tous les champs NAV-PVT remontent
correctement avant tout test circuit.

1. Brancher le montage au Pixel 8 via le câble USB-C OTG. L'app se lance automatiquement
   (intent-filter `USB_DEVICE_ATTACHED`) ; sinon l'ouvrir manuellement, elle détecte le
   périphérique déjà branché au démarrage (`onStart`). Le bouton **Reconnecter** relance la
   séquence sans avoir à débrancher.
2. Accorder la permission USB si demandée (dialogue système Android, à accepter une fois).
3. Suivre l'enchaînement des états en haut de l'écran : *Recherche du module* → *Détection de la
   vitesse* (l'appli annonce chaque baud testé) → *Configuration du module* → **Connecté**, avec
   le baud retenu et la cadence soutenable affichés juste en dessous. Si ça s'arrête sur une
   erreur, la fenêtre **Trafic brut** en bas de l'écran dit ce qui est réellement arrivé sur le
   port (phrase NMEA lisible, octets en hexa, ou "octets illisibles" = mauvaise vitesse).
4. Attendre `fixType = 3D` avec `numSV` suffisant (idéalement ≥ 6-8) et `hAcc` qui descend sous
   quelques mètres — sur un balcon dégagé, généralement en moins d'une minute ; peut être plus
   long en vue ciel partiellement masquée.
5. Vérifier à l'écran que **tous les champs affichés bougent de façon cohérente** :
   - `fixType`/`numSV`/`hAcc` : cohérents avec un ciel dégagé.
   - Cadence affichée : viser ~25Hz (`40 ms`) une fois le fix stable — une cadence bloquée à 1Hz
     malgré la config envoyée indiquerait que la carte ignore `CFG-RATE`.
   - Vitesse Doppler : à l'arrêt sur le balcon elle doit rester proche de 0 (bruit normal
     quelques cm/s à quelques dizaines de cm/s selon l'environnement).
6. Démarrer l'enregistrement (bouton "Démarrer l'enregistrement"), puis **marcher dans la rue**
   à différentes allures (marche lente, marche rapide, quelques pas de course) en gardant le
   téléphone/module dans la poche poitrine comme prévu pour un test kart. Observer que la
   vitesse Doppler affichée suit ces changements d'allure de façon plausible (quelques km/h à
   ~15-20 km/h de course à pied). L'écran reste allumé pendant l'enregistrement (pas encore de
   service en arrière-plan : ne pas quitter l'appli en cours de session).
7. Arrêter l'enregistrement, puis "Partager" pour envoyer le CSV (email, Drive, etc.) et
   l'ouvrir avec le pipeline Python existant.

### Si ça ne marche pas

| Symptôme à l'écran | Cause la plus probable |
|---|---|
| "Aucun périphérique USB série détecté" | Câble OTG non fonctionnel, adaptateur non alimenté, ou câble USB de charge seule (sans données) |
| Détection qui balaie tous les bauds sans rien trouver | TX/RX non croisés, soudure froide, ou GND non relié |
| "octets illisibles" dans le trafic brut à toutes les vitesses | Niveau logique incompatible, ou masse commune absente |
| NMEA visible mais cadence bloquée à 1Hz | La carte a ignoré `CFG-RATE` ou `CFG-PRT` — voir le message d'erreur affiché |
| Fix qui ne passe jamais en 3D | Antenne non vissée, ou vue ciel trop masquée — sortir à découvert |

## Format du log CSV

Un fichier par session, dans `Android/data/com.kartland.gnsslogger/files/logs/` sur le
téléphone (accessible aussi via le bouton "Partager"), nommé `gnss_AAAAMMJJ_HHmmss.csv`.

Colonnes (une ligne par solution NAV-PVT) :

```
utc_iso, phone_elapsed_ns, itow_ms, fix_type, fix_ok, num_sv,
lat_deg, lon_deg, height_m, hmsl_m, h_acc_m, v_acc_m,
vel_n_mps, vel_e_mps, vel_d_mps, speed_mps, heading_deg,
speed_acc_mps, heading_acc_deg, pdop
```

- `phone_elapsed_ns` : horloge monotone du téléphone (`SystemClock.elapsedRealtimeNanos()`) au
  moment du décodage — utile pour l'analyse de cadence locale, indépendante de l'horodatage GNSS.
- `itow_ms` : temps récepteur (time-of-week), la référence la plus fiable pour l'espacement
  inter-solutions.
- `speed_mps` / `speed_acc_mps` : `gSpeed`/`sAcc`, dérivées Doppler — les champs clés de cette
  expérimentation.

Ce format est volontairement proche des logs GnssLogger déjà traités par `traces.py`/`gate.py`,
mais les noms de colonnes n'ont pas pu être vérifiés à l'identique dans cette session (fichiers
non disponibles ici). Si le chargeur existant attend d'autres noms, un petit renommage
pandas (`df.rename(columns=...)`) au chargement suffira probablement — pas besoin de refaire ce
logger pour ça.

## Structure du projet

```
settings.gradle, build.gradle, gradle.properties
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/com/kartland/gnsslogger/
    UbxProtocol.kt      — trames UBX, checksum, config (RATE/MSG/PRT), parsing NAV-PVT
    UbxFrameParser.kt   — machine à états pour extraire les trames d'un flux d'octets USB
    NmeaSniffer.kt      — validation de phrases NMEA (détection de baud + signe de vie)
    UsbGnssManager.kt   — USB, détection de baud, configuration, lecture, cycle de vie
    CsvLogger.kt        — écriture du log CSV
    MainActivity.kt     — UI (états de connexion, statut live, start/stop/partage, trafic brut)
app/src/main/res/       — layout, strings (FR), thème, device_filter, file_paths
```
