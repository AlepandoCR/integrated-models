# Guía de Integrated Models

Documentación de uso para desarrolladores que van a consumir esta API y para operadores que van a
configurarla. En español; los identificadores, las claves de configuración y el contenido de los bloques
de código siguen en inglés, porque la API y los ficheros lo están.

Integrated Models es una **librería-plugin**: no hace nada por sí sola. Envuelve a
[BetterModel](https://github.com/toxicity188/BetterModel) y le añade rigs con física, un ciclo de marcha,
decoraciones, vehículos y una capa de IA. Los modelos `.bbmodel` viven en la carpeta de BetterModel; los
rigs viven aquí y apuntan a ellos por nombre.

## Por dónde empezar

| | |
| --- | --- |
| [00 · Instalación](00-instalacion.md) | Requisitos, instalar el plugin, consumir la API, construir. **Empieza aquí.** |
| [01 · Modelos](01-modelos.md) | Obtener el servicio y poner un rig sobre una entidad |
| [02 · Decoraciones](02-decoraciones.md) | Un rig sin entidad debajo: farolas, estandartes, estatuas |
| [03 · Ciclo de vida](03-ciclo-de-vida.md) | Qué pasa al desactivar y al recargar, y **las reglas de hilos que sí tienes que respetar** |
| [04 · Rigs](04-rigs.md) | El formato de fichero completo, con la política de recorte-y-aviso |
| [05 · Física y movimiento](05-fisica-y-movimiento.md) | Las dos capas, los 14 roles de extremidad, y qué está calibrado y qué es suposición |
| [06 · Vehículos](06-vehiculos.md) | Montar, conducir, y la trampa de autoridad cliente/servidor |
| [07 · IA](07-ia.md) | Comportamiento por `Brain` de NMS, y por qué puede estar ausente |
| [08 · Configuración](08-configuracion.md) | Referencia de las claves, y qué **no** es configurable |
| [09 · Construir rigs](09-construir-rigs.md) | Cómo se escribe un rig mirando un modelo concreto, y qué hace cada parte ya dentro del juego. El compañero práctico de 04 |
| [10 · Entrada del jugador](10-inputs.md) | Las doce entradas, las combinaciones, y qué fuente puede **suprimir** |
| [11 · Configuración del consumidor](11-config-del-consumidor.md) | Los mensajes de tu vehículo, y por qué **no** heredas pandastic-core |

Para recargar la configuración sin reiniciar: **`/imreload`**. Está documentado en
[00 · Instalación](00-instalacion.md#recargar-sin-reiniciar).

Si solo vas a leer dos, lee **01** y **03**. El primero es el camino feliz; el segundo es todo lo que te
puede morder. Si vas a escribir un rig, lee **09** antes que **04**.

## Lo que todavía no funciona

Esta sección existe porque una guía que describe lo que el código *pretende* hacer, y no lo que hace, le
cuesta a un lector una tarde. Está escrita contra el código, no contra las intenciones, y se corrige
cuando el código cambia.

- **El culling por observador.** La capa de decisión existe y está probada; no hay bucle que la ejecute
  ni muestreo de jugadores, y el culler no se referencia desde ningún sitio fuera de su propio paquete.
  Las 11 claves de `view.yml` son inertes, y hoy una decoración solo la ve el jugador al que un
  consumidor le llama `show`.
- **Los asientos de un vehículo.** El mecanismo está completo —se resuelven al arrancar, se loguea un
  seat chart, cada jinete va sobre un portador invisible que sigue a su locator— pero un asiento es un
  **locator** y **ninguno de los modelos de este repositorio contiene uno**. No hay fallback al hueso ni
  a la entidad raíz, así que hoy `Vehicle.mount(player)` devuelve `false` y **no se puede montar en
  ningún vehículo**. Se arregla en Blockbench, no aquí; ver [09 §7](09-construir-rigs.md).
- **El ciclo de marcha, a medias.** Ya **sí** se adjunta: un `RiggedModel` cuyo rig usa los nombres de
  hueso convencionales recibe el ciclo de marcha configurado, con los segmentos medidos sobre el modelo
  vivo. Lo que no existe es control desde el fichero — el formato de rig no describe roles, ni longitudes
  de segmento, ni `facing`, la lista de nombres reconocidos está fija en el código, y no hay forma de
  desactivarlo desde un YAML. Ver [09 §10](09-construir-rigs.md).

Lo que **sí** funciona y antes figuraba aquí:

- **Los menús.** `/immodels` abre el vestuario de modelos para un jugador, con sus 80 claves de
  `menu.yml` en vigor.
- **Las animaciones propias del modelo.** La sección `clips` ya se consume: `ClipSelector` elige
  `idle`/`walk`/`run` por la velocidad del dueño, con histéresis para que no parpadee en el umbral, y
  `render.yml: animationOwnedBonePolicy` ya se lee de verdad con sus tres respuestas. Marcar un hueso
  como `animation-owned` **sí** cambia lo que hacemos con él.

  Con un límite que conviene conocer, porque es del renderer y no nuestro: de la pose animada viva solo
  es alcanzable la **rotación**, por un accesor público de nombre engañoso. La **posición y la escala no
  lo son** — no tienen ventana pública y la variante en espacio de mundo no se puede invertir. Así que un
  clip que keyframea la *posición* de un hueso no se puede reproducir, y por eso el default sigue siendo
  `RENDERER`: todos los ciclos de marcha de estos modelos keyframean posición en el torso.
- **La entrada del jugador.** Las doce entradas se observan y se publican, y el controller es alcanzable
  en `IntegratedModels.getInputController()`. Ver [10](10-inputs.md) para qué fuente puede suprimir qué.

Una cosa más que conviene saber antes de empezar: **todos los modelos salen de un único tipo raíz,
`Model`.** Un `RiggedModel` sobre una entidad, una `Decoration` sin nada debajo, un `Vehicle` que alguien
monta y un `EntityModel` de la API de componentes son los cuatro un `Model`: misma identidad, mismo
`despawn()`/`close()`, mismo `isSpawned()`, mismo vocabulario de apariencia. Y `getModelManager()` es el
registro de todos ellos, que los binders rellenan solos — para poner un rig en el mundo sigues usando los
binders de [01](01-modelos.md).

## Lo que no está validado contra un servidor real

Nada de este proyecto se ha ejecutado contra un Folia 26.3 con BetterModel instalado. Todo lo que las
guías afirman sale de firmas reales y de invariantes del código, nunca de haberlo visto funcionar. Cuatro
cosas concretas esperan esa confirmación, y están señaladas donde toca: la pose de reposo reconstruida,
si el transformador de display copia sus vectores, el camino de paquetes de punta a punta, y cómo se
entrelaza un hueso que BetterModel anima y nosotros posamos a la vez.

Las constantes cosméticas de la marcha — amplitudes, balanceo, respiración, los ángulos del tobillo —
fueron **elegidas, no medidas**, y nunca se han visto sobre un modelo. Son claves de `gait.yml`, así que
ajustarlas es editar un YAML, no recompilar. [05](05-fisica-y-movimiento.md) dice exactamente cuáles.
