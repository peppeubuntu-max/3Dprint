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

1. Fissare rigidamente il telefono in qualsiasi orientamento: verticale,
   orizzontale, piatto o inclinato. Il telefono deve restare solidale alla moto.
2. Premere Avvia e consentire la posizione precisa.
3. A moto ferma, verticale, su piano e con manubrio dritto, attendere almeno
   un secondo e premere «1/2 Moto verticale: imposta zero».
4. Sempre da fermo e senza sterzare, inclinare la moto leggermente verso
   DESTRA (5–30°), sostenerla stabilmente e confermare dopo un secondo.
   Effettuare la procedura in un luogo sicuro, con aiuto se necessario.
5. Riportare la moto verticale. La calibrazione è valida per il giro corrente;
   ripeterla se cambia il supporto. Un solo zero non identifica l'asse di marcia.
6. Stop salva il giro; Giri permette di consultarlo ed esportare il GPX.

La conferma richiede orientamento e giroscopio aggiornati, stabilità per un
secondo e assenza di movimento GPS noto. È comunque responsabilità di chi
calibra tenere la moto ferma e usare le due posizioni corrette: i sensori non
possono riconoscere una calibrazione fatta a sinistra invece che a destra.
Senza calibrazione completa la piega non viene mostrata e l'accelerazione
rimane la stima GPS etichettata. Non ruotare il telefono nel supporto dopo
la calibrazione: rotazione dello schermo e rotazione fisica sono diverse.

La piega deriva dal rotation vector del telefono, non da una IMU da moto:
accelerazioni laterali, vibrazioni, fusione sensori e geometria del supporto
possono introdurre errori importanti in curva. Non è una misura certificata;
serve una validazione dinamica con strumento di riferimento prima di poter
garantire accuratezza. Non sono simulati dati quando il sensore è assente.

L'accelerazione usa il sensore lineare a frequenza richiesta di circa 50 Hz,
con filtro passa basso (costante di tempo 0,15 s) e registrazione a circa 10 Hz.
La frequenza reale dipende dal dispositivo. 1 G = 9,80665 m/s²; accelerazione
positiva e frenata negativa. La calibrazione ricava un riferimento 3D ortonormale dagli orientamenti
verticale e inclinato a destra della moto. Il prodotto vettoriale definisce
l'asse longitudinale; accelerazione e piega vengono proiettate negli assi
della moto. Non vi sono assunzioni sul verso dello schermo. Gli assi restano
fissi rispetto al supporto durante il giro. La procedura richiede solo rollio,
non sterzata o variazioni di pendenza fra le due posizioni. Un supporto sul
manubrio può muoversi rispetto al telaio quando si sterza, introducendo errori.
Non è il modulo dell'accelerazione
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
azzeramento angolare, scala colori, calibrazione 3D su 80 montaggi simulati, proiezione asse di marcia, conversione
in G, filtro inerziale, picchi e struttura GPX con telemetria.

**Build APK e test unitari completati con successo su GitHub Actions il
24 ottobre 2026.** Esecuzione: https://github.com/peppeubuntu-max/3Dprint/actions/runs/40102489399
Commit compilato: `800d3417ca5e72c9937f48df10835d3e44d618e0`.
Il proxy locale non è utilizzabile; la compilazione è avvenuta sul runner cloud.
APK debug per prove, non ancora collaudato su dispositivo reale.

Prima dell'uso verificare su telefono: permessi negati/posizione approssimata,
GPS spento o perso, assenza sensore, azzeramento e inclinazione sui due lati,
segno G in accelerazione/frenata, supporto inclinato, sensore assente,
pausa/spostamento/ripresa, schermo spento per almeno 20 minuti, arresto processo
e recupero, export GPX in un lettore esterno, spazio esaurito, giri lunghi.
Per giri molto lunghi la persistenza JSON e le singole linee della mappa
richiedono successiva ottimizzazione (database e rendering aggregato).


## Versione 0.2: mappe e riproduzione del giro

La posizione viene richiesta all'apertura dell'app, senza avviare un giro.
Il listener è attivo solo quando la schermata è visibile. Dopo un rifiuto dei
permessi è disponibile il collegamento alle impostazioni. La cache OSM usa
le directory interne dell'app, senza permessi di archiviazione esterna.

Nel dettaglio, lo slider seleziona il tempo trascorso dall'avvio del giro,
comprese le pause. I pulsanti ±1 s permettono la selezione precisa. Si mostra
il campione GPS più vicino entro 1 s e quello inerziale entro 200 ms; fuori
soglia i valori sono assenti, senza interpolare le pause. Il marker segue il
campione selezionato. Il pannello dei dettagli è scorrevole sui piccoli schermi.

Google Maps è integrato ma viene attivato solo compilando con MAPS_API_KEY
non vuota. Il workflow legge il secret GitHub omonimo. In sua assenza resta
attiva OpenStreetMap, per avere una build utilizzabile senza credenziali.
Con Google abilitato, il pulsante Mappa alternativa consente di usare OSM
anche in caso di problemi di rete o autorizzazione Google.

La chiave va creata in un progetto Google Cloud con Maps SDK for Android
abilitato e account di fatturazione configurato secondo i requisiti Google.
Limitarla all'API Maps SDK for Android e all'app Android `it.mototrack` con
l'impronta SHA-1 del certificato di firma. Non va pubblicata nei sorgenti.
La chiave è inclusa nell'APK e non è un segreto dell'utente finale: le
restrizioni Android/API sono essenziali. Tutti i telefoni usano la stessa
chiave; nessun inserimento manuale da parte di chi installa l'app.

Per distribuzione e aggiornamenti occorre una chiave di firma persistente,
conservata privatamente. Gli attuali runner generano certificati debug
indipendenti: questo APK può richiedere di disinstallare la vecchia build.
Esportare prima i giri in GPX: la disinstallazione rimuove i dati locali.
Non usare la firma debug temporanea per una distribuzione definitiva.

Versione 0.3: calibrazione indipendente dal montaggio, controlli di stabilità e pannello registrazione scorrevole. Validazione fisica su moto ancora necessaria.

## Versione 0.4: interfaccia Material 3

Menu laterale con Registra e Giri, barra superiore con ritorno dal dettaglio,
comandi di registrazione persistenti in basso e schede per telemetria e riepilogo.
Legenda colori e testi esplicativi fissi rimossi. La calibrazione guidata è
accessibile dalla finestra Calibra sensori. Target dei comandi principali di
almeno 56 dp, navigazione con Indietro, etichette per lettori di schermo,
sezione e giro selezionato conservati durante la ricreazione dell'attività.
Archivio con stato di caricamento, errore/riprova e stato vuoto con accesso a
Registra. Il dettaglio scorre per rendere accessibili slider e dati su schermi piccoli.


## Versione 0.5: calibrazione visuale e applicazione alla registrazione

Aprire Calibra sensori dopo l'avvio. Il giro viene messo temporaneamente in
pausa: i movimenti della calibrazione non producono punti GPS o campioni G.
La procedura mostra una moto vista da dietro, un valore angolare e la barra
di stabilità. Si riutilizza la calibrazione precedente se si annulla.

1. Moto verticale e ferma, manubrio dritto. Il numero iniziale è l'assetto del
   telefono (angolo della normale allo schermo rispetto alla verticale),
   NON la piega della moto. Attendere la barra e premere Imposta questa posizione a 0°.
2. Inclinare la moto a destra tra 5° e 30° senza sterzare. Il numero ora è
   l'angolo rispetto allo zero acquisito. Confermare quando stabile.
3. Riportare la moto verticale. Entro ±3° di piega e 4° di scarto complessivo
   dalla posizione iniziale, confermare Verifica e applica lo zero.
4. La guida mostra zero impostato e piega live. Il giro riprende se era in
   registrazione; resta in pausa se lo era già prima della procedura.

Il riferimento finale usa una media delle letture stabili; anche l'eventuale
bias dell'accelerazione lineare da fermo viene sottratto alle successive
misure. La finestra richiede almeno 0,8 s, scarto massimo di 2,5° dalla media,
assenza di accelerazioni lineari superiori a 0,8 m/s² (quando disponibili) e
assenza di movimento GPS noto oltre 2 m/s. I dati scaduti non abilitano la
conferma. Non si richiede più un flusso separato dal giroscopio: viene usato
GAME_ROTATION_VECTOR, oppure ROTATION_VECTOR se il primo non è disponibile.

Una sessione provvisoria non sostituisce la calibrazione attiva finché tutte
le verifiche non sono complete. Pausa e ripresa azzerano solo il filtro
transitorio, mantenendo gli assi e il bias. I valori live e quelli salvati
usano lo stesso motore CalibratedTelemetry; GPS e GPX conservano le unità
precedenti. Test automatici coprono tremore, gradi live, zero finale, scadenza,
movimento, annullamento e applicazione dei valori al riepilogo e al GPX dopo
il reset del filtro. Non sostituiscono una prova fisica su telefono e moto.

Questa procedura sostituisce le istruzioni della calibrazione in due passaggi
nelle sezioni delle versioni precedenti. Calibrare in sicurezza a moto ferma,
con sostegno adeguato, senza inclinare il telefono rispetto al supporto.
