# MotoTrack — Android

Prima implementazione nativa in Kotlin e Jetpack Compose, interfaccia italiana.
Android 8.0+ (API 26), target Android 15 (API 35).

## Funzioni

- Mappa OpenStreetMap con percorso a segmenti colorati: verde a 0 km/h,
  giallo a 60, rosso a 120 o più. Grigio per velocità non disponibile.
- Avvia, pausa, riprendi, stop e salva. La pausa non collega i segmenti e
  non contribuisce al tempo attivo o alla distanza.
- Servizio foreground con notifica per registrare anche lasciando l'app o
  spegnendo lo schermo, soggetto alle politiche energetiche del dispositivo.
- Velocità GPS, stima piega laterale con azzeramento, accelerazione e frenata
  longitudinali in G dal sensore Android TYPE_LINEAR_ACCELERATION (gravità
  già rimossa), con stima GPS di riserva esplicitamente indicata.
- Giri salvati localmente, percorso complessivo, distanza, durata attiva,
  velocità massima/media, piega massima e picchi di accelerazione/frenata.
- Toccare una linea del percorso mostra la telemetria del campione finale.
- Esportazione GPX tramite selettore Android, senza permessi di archiviazione.
  I segmenti rispettano pause e interruzioni GPS superiori a 10 secondi.
  Le estensioni XML contengono velocità (m/s), piega (gradi), accelerazione
  (m/s² e G), sorgente sensore/GPS e precisione GPS (metri).
  Un’estensione della traccia include inoltre i campioni inerziali a circa 10 Hz
  con tempo UTC, G e segmento, anche quando manca una posizione GPS. Altre app potrebbero ignorare le estensioni.
- Salvataggio atomico ogni circa 5 secondi, in pausa e allo stop. Le tracce
  interrotte restano nell'archivio ed esportabili; gli ultimi secondi non
  ancora salvati possono andare persi. Nessun riavvio automatico dopo un arresto.

## Aprire e compilare

Richiede Android Studio Meerkat o successivo, JDK 17+, SDK Android 35 e
Gradle 8.11.1. In Android Studio aprire questa cartella come progetto Gradle,
selezionare una distribuzione locale Gradle 8.11.1 se richiesta e installare
SDK 35 tramite SDK Manager. Nessuna chiave API per le mappe.

Il repository non include il wrapper binario Gradle. Con Gradle 8.11.1 installato:

```sh
gradle wrapper --gradle-version 8.11.1
./gradlew testDebugUnitTest assembleDebug
```

Configurare `sdk.dir=/percorso/android/sdk` in `local.properties` oppure
`ANDROID_HOME`. L'APK debug sarà in `app/build/outputs/apk/debug/app-debug.apk`.

## Uso e limiti delle misure

1. Fissare rigidamente il telefono in orientamento portrait, bordo superiore
   verso l'alto e schermo rivolto al pilota. Supporti quasi orizzontali non
   sono gestiti; la proiezione della gravità sullo schermo deve essere sufficiente.
2. Premere Avvia e consentire la posizione **precisa**. Le notifiche sono
   consigliate; il rifiuto della notifica non blocca la registrazione.
3. A moto ferma e verticale, premere Imposta zero piega e asse G. Ricalibrare se
   cambia il fissaggio. La calibrazione vale per il giro corrente.
4. Attendere il GPS; stop salva anche un giro senza punti (es. GPS assente).
5. Aprire Giri, scegliere il giro e scaricare il GPX.

La piega deriva dal rotation vector del telefono, non da una IMU da moto:
accelerazioni laterali, vibrazioni, fusione sensori e geometria del supporto
possono introdurre errori importanti in curva. Non è una misura certificata;
serve una validazione dinamica con strumento di riferimento prima di poter
garantire accuratezza. Non sono simulati dati quando il sensore è assente.

L'accelerazione usa il sensore lineare a frequenza richiesta di circa 50 Hz,
con filtro passa basso (costante di tempo 0,15 s) e registrazione a circa 10 Hz.
La frequenza reale dipende dal dispositivo. 1 G = 9,80665 m/s²; accelerazione
positiva e frenata negativa. La calibrazione ricava l'asse di marcia dalla
normale dello schermo proiettata sul piano orizzontale a moto ferma: telefono
allineato all'asse della moto, schermo rivolto al pilota. Questo asse resta
fisso rispetto al supporto durante il giro. Non è il modulo dell'accelerazione
su tre assi: curve e buche vengono escluse solo nella misura in cui la
proiezione e la rimozione della gravità sono accurate. Vibrazioni e pendenze
possono lasciare errori; il filtro attenua anche i picchi più brevi.

Prima della calibrazione, senza sensore lineare/orientamento, o con campioni
inerziali mancanti, viene usata la derivata GPS (circa 1 Hz), convertita in G
ed etichettata GPS. Il dato inerziale scade dopo 1 secondo; sul punto mappa
viene associato solo se entro 1 secondo dal fix GPS. I vecchi giri restano
leggibili: le accelerazioni salvate in m/s² vengono convertite in G nella UI.

I picchi del riepilogo provengono esclusivamente dai campioni inerziali quando
presenti (coprono solo i periodi calibrati con sensore funzionante), altrimenti
sono stimati dal GPS. La pausa sospende i campioni e resetta il filtro. Il GPX
conserva tutti i campioni inerziali e la sorgente dei dati associati ai punti.
Distanza e velocità media includono solo la traccia disponibile e il tempo
attivo (anche in attesa GPS).

Le mappe richiedono Internet per le tile non in cache e rispettano i termini
OpenStreetMap: https://operations.osmfoundation.org/policies/tiles/ .
Nessun download massivo/offline. L'attribuzione è visibile sulla mappa.
Le richieste tile raggiungono il fornitore OSM; le tracce sono salvate nel
dispositivo e vengono condivise solo attraverso l'esportazione scelta.

## Verifica

Inclusi test JUnit per distanza, segmenti pausa, accelerazione/frenata,
azzeramento angolare, scala colori, proiezione asse di marcia, conversione
in G, filtro inerziale, picchi e struttura GPX con telemetria.

**Build e test Android non eseguiti in questo ambiente:** SDK e Gradle non
sono preinstallati e il proxy configurato rifiuta le connessioni necessarie
al download. Il progetto non va considerato collaudato su dispositivo.

Prima dell'uso verificare su telefono: permessi negati/posizione approssimata,
GPS spento o perso, assenza sensore, azzeramento e inclinazione sui due lati,
segno G in accelerazione/frenata, supporto inclinato, sensore assente,
pausa/spostamento/ripresa, schermo spento per almeno 20 minuti, arresto processo
e recupero, export GPX in un lettore esterno, spazio esaurito, giri lunghi.
Per giri molto lunghi la persistenza JSON e le singole linee della mappa
richiedono successiva ottimizzazione (database e rendering aggregato).
