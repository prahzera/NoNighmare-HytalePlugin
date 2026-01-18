# NoNightmare Plugin - Hytale

## Descripción

NoNightmare es un plugin para Hytale que permite pasar la noche con solo un **porcentaje configurable** de jugadores durmiendo. Además, controla el ciclo de sueño/amanecer, con mensajes profesionales en el chat y un delay configurable.

### Características

- ✅ **Porcentaje configurable**: Por defecto 50% de jugadores durmiendo
- ✅ **Mensajes en chat**: Estado, umbral alcanzado, cancelación y amanecer
- ✅ **Delay configurable**: Evita el salto instantáneo a día
- ✅ **Horario nocturno exacto**: Configurable por hora/minuto
- ✅ **Comando de recarga**: `nonightmare reload`
## Clase principal

### **NoNightmarePlugin.java**
Responsable de:
- Cargar configuración desde `nonightmare.json`
- Registrar el comando `nonightmare`
- Ejecutar el poller en el hilo del mundo
- Evaluar sueño con componentes nativos
- Enviar mensajes al chat con estilo/colores

## Instalación

### Compilación Manual (Recomendado)
Si Gradle tiene problemas, usa el script de compilación:

```bash
bash build.sh
```

Esto creará `NoNightmare-1.0.jar`

### Con Gradle
```bash
./gradlew build
```

El JAR se creará en `build/libs/NoNightmare-1.0.jar`

### Instalación en el Servidor

1. Copia `NoNightmare-1.0.jar` a la carpeta `mods` del servidor Hytale
2. Reinicia el servidor
3. Verás logs de inicio y estado en consola

## Configuración

El plugin crea `nonightmare.json` en el directorio de datos del plugin.

```json
{
  "requiredSleepPercent": 50.0,
  "skipDelaySeconds": 2,
  "nightStartHour": 18,
  "nightEndHour": 4,
  "nightStartTime": "18:00",
  "nightEndTime": "04:47",
  "notAllowedCooldownSeconds": 5,
  "messageSleepStatus": "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#E5E7EB}Durmiendo: {#22C55E}{bold}{sleeping}{/bold}{#9CA3AF}/{#E5E7EB}{total} {#9CA3AF}({#38BDF8}{bold}{percent}{/bold}%{#9CA3AF} / {#F59E0B}{bold}{required}{/bold}%{#9CA3AF})",
  "messageThresholdReached": "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#E5E7EB}Umbral alcanzado. Amanecerá en {#F59E0B}{bold}{delay}{/bold}{#F59E0B}s",
  "messageThresholdLost": "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#EF4444}{bold}El umbral dejó de cumplirse.{/bold}",
  "messageNightSkipped": "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#22C55E}{bold}¡Buenos días!{/bold} {#E5E7EB}Se alcanzó {#38BDF8}{bold}{percent}{/bold}% {#9CA3AF}({#22C55E}{bold}{sleeping}{/bold}{#9CA3AF}/{#E5E7EB}{total}{#9CA3AF})",
  "messageSleepNotAllowed": "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#F59E0B}{bold}Solo puedes dormir para hacer de Día durante la noche.{/bold}"
}
```

Notas:
- Si `nightStartTime`/`nightEndTime` están presentes, se usan esos valores.
- Si no están, se usa `nightStartHour`/`nightEndHour`.
- El mensaje “Solo puedes dormir para hacer de Día durante la noche” aparece
  cuando alguien se acuesta fuera del horario nocturno.
  
### Plantillas de mensaje

Variables disponibles:
`{sleeping}`, `{total}`, `{percent}`, `{required}`, `{delay}`.

Estilos disponibles:
`{#RRGGBB}` para color, `{bold}` `{/bold}`, `{italic}` `{/italic}`,
`{mono}` `{/mono}` y `{reset}`.

### Comandos

- `nonightmare reload` recarga la configuración sin reiniciar el servidor.

## Mensajes de chat

Se emiten mensajes profesionales en el chat con colores:
- Estado de sueño actual
- Umbral alcanzado (con delay)
- Umbral perdido
- “¡Buenos días!” al amanecer

## Versión

**v1.2.0** - 18 de Enero, 2026

## Licencia

MIT

## Autor
**prahzera**

Desarrollado para Hytale
