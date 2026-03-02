# Balanza BT - App Android puente

App de segundo plano que lee la balanza por Bluetooth clásico
y envía los datos al backend: **https://bazsoft.sosnegocios.com/apiBalanza**

---

## Cómo obtener el APK (sin instalar Android Studio)

### Paso 1 - Subir a GitHub

1. Ve a **github.com** e inicia sesión
2. Clic en **"New repository"**
3. Nombre: `BalanzaBT`
4. Clic en **"Create repository"**
5. En la página del repositorio vacío, clic en **"uploading an existing file"**
6. **Arrastra toda la carpeta** `BalanzaBT` o sube los archivos uno a uno
7. Clic en **"Commit changes"**

### Paso 2 - Esperar la compilación automática

1. Ve a la pestaña **"Actions"** en tu repositorio
2. Verás el workflow **"Build APK"** ejecutándose (tarda ~5 minutos)
3. Cuando termine verás ✅ verde

### Paso 3 - Descargar el APK

1. Clic en el workflow completado
2. Baja hasta **"Artifacts"**
3. Clic en **"BalanzaBT-APK"** para descargar el ZIP
4. Extrae el ZIP → obtienes `app-debug.apk`

### Paso 4 - Instalar en la tablet Android

1. Copia `app-debug.apk` a la tablet (por USB, email, Google Drive, etc.)
2. En la tablet: **Ajustes → Seguridad → Instalar apps desconocidas → Permitir**
3. Abre el APK y sigue la instalación

---

## Cómo usar la app

1. **Empareja la balanza** en Ajustes Bluetooth de Android primero
2. Abre **"Balanza BT"**
3. Selecciona tu balanza de la lista
4. Presiona **▶ INICIAR SERVICIO**
5. Ya puedes **minimizar la app** — sigue corriendo en segundo plano
6. Verás una notificación persistente en la barra de Android

### La app se reconecta automáticamente si:
- La balanza pierde señal
- Android reinicia la tablet
- El Bluetooth se desconecta momentáneamente

---

## Endpoint que usa la app

```
POST https://bazsoft.sosnegocios.com/apiBalanza/balanza/peso
Content-Type: application/json

{
  "peso": "  005.230",
  "estado": "Estable",
  "codigo": "B"
}
```

---

## Estructura del proyecto

```
BalanzaBT/
├── app/src/main/
│   ├── java/com/bazsoft/balanzabt/
│   │   ├── service/
│   │   │   ├── BalanzaForegroundService.kt  ← Lógica principal
│   │   │   └── BootReceiver.kt              ← Arranque automático
│   │   └── ui/
│   │       └── MainActivity.kt              ← Pantalla de selección
│   ├── res/layout/activity_main.xml
│   └── AndroidManifest.xml
├── .github/workflows/build.yml              ← Compilación automática
└── README.md
```
