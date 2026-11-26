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
| [12 · El controller de un vehículo](12-controlador-de-vehiculo.md) | Comportamiento **con estado** encima de un vehículo: los cinco hooks, sus hilos, y cuándo esto **no** es lo que quieres |
| [13 · Estado del servidor](13-estado-del-servidor.md) | `/imhealth` y `/imstats`, qué significa cada cifra, y **las que el informe declara desconocidas** |
| [14 · Rendimiento y coste](14-rendimiento-y-coste.md) | Por qué Paper lo siente más que Folia, qué corre fuera del tick y qué **no puede**, lo que cuesta un modelo y un vehículo por paso, y qué subir primero |

Cuatro comandos, y son los cuatro que hay: **`/imreload`** para recargar sin reiniciar (documentado en
[00 · Instalación](00-instalacion.md#recargar-sin-reiniciar)), **`/imhealth`** y **`/imstats`** para
preguntarle al plugin qué está haciendo ([13](13-estado-del-servidor.md)), y **`/immodels`** para abrir el
vestuario. Los tres primeros son solo operador por defecto, porque sus permisos no están declarados y el
defecto de Bukkit para un permiso no declarado es OP; **`/immodels` no pide ninguno** y lo puede usar
cualquiera.

Si solo vas a leer dos, lee **01** y **03**. El primero es el camino feliz; el segundo es todo lo que te
puede morder. Si vas a escribir un rig, lee **09** antes que **04**. Si vas a hacer un vehículo, lee **06**
entero antes de escribir una línea, y después **12** si tiene comportamiento propio.

## Lo que todavía no funciona

Esta sección existe porque una guía que describe lo que el código *pretende* hacer, y no lo que hace, le
cuesta a un lector una tarde. Está escrita contra el código, no contra las intenciones, y se corrige
cuando el código cambia.

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
- **El balanceo de un vehículo, a medias.** La capa de terreno calcula el roll, lo mezcla, lo acota y lo
  prueba — y el backend que se envía **lo descarta**, porque la rotación de modelo completo del renderer
  son dos componentes, pitch y yaw, y no hay dónde poner un tercero. Así que un vehículo que balancea
  cabecea, gira y se renderiza **derecho**. No es configuración y no tiene arreglo en un YAML; las dos
  alternativas se consideraron y las dos son peores. Ver
  [06 §5](06-vehiculos.md#tres-límites-honestos-de-lo-que-hay-hoy).
- **Subir escalones necesita el adaptador NMS, que ya existe pero puede no estar.** `moveLocal` está
  implementado en la capa NMS: un jar construido con ella escribe en el sitio, con colisión, llevando el
  vertical — así que el escalón **llega a la pantalla**. Un jar construido con
  `-PintegratedModels.nms=false`, o una versión de Minecraft sin adaptador, no tiene `moveLocal`: cada paso
  es una relocalización, la relocalización tira el vertical a propósito, el vehículo sigue conduciendo y
  **no trepa**. Y una segunda condición, ésta sí configurable: un vehículo que **nadie ve** tampoco trepa,
  porque el escalón lo acarrea la misma lectura de terreno que la inclinación y
  `vehicle.yml: terrain.sampleOnlyWhenSeen` la suprime. Se repara al observarlo. Ver
  [14 §6](14-rendimiento-y-coste.md#6-el-deadband-del-muestreo-de-terreno).
- **De `VehicleKind`, dos preguntas no las lee nadie todavía.** `hasFreeVerticalMotion()` e `isBuoyant()`
  están publicadas y **ningún código del plugin las consulta**, así que hoy `SUBMARINE` y `BOAT` honran
  exactamente los mismos términos de terreno y el solver no los distingue. Elegir `SUBMARINE` te da el
  cabeceo por aceleración de un barco y nada más. Y un `FLYER` no puede ganar altura desde esta capa: el
  salto se transporta y no se consume, y un pilot sintético no lo pulsa nunca.
- **El seam de navegación de la IA sigue vacío.** `NavigationBridge` está declarado y sin métodos, así que
  para una criatura que no es la raíz de un vehículo el consumidor del `AiIntent` sigues siendo tú. Lo que
  **sí** cambió: la capa de vehículos es ahora el primer consumidor del intent dentro del plugin, por
  `AiPilot`. Ver [07 §10](07-ia.md#10-conducir-un-vehículo-con-un-plan).

Lo que **sí** funciona y antes figuraba aquí:

- **El culling por observador.** Ya hay bucle: una pasada fuera del tick a 10 Hz y un muestreador por
  jugador en el `EntityScheduler` de ese jugador. Las 11 claves de `view.yml` están **en vigor**. Lo que
  compra no es uniforme y conviene saberlo: para una **decoración** ahorra los paquetes **y** la
  simulación; para un **modelo atado a una entidad** ahorra **solo la simulación**, porque quién recibe las
  actualizaciones de los huesos de una entidad ya lo decide el servidor — con la consecuencia de que un
  jugador que todavía tiene los huesos de un modelo suspendido lo ve dejar de moverse. Y el **muestreador
  por entidad no se para nunca**, porque es lo que publica la posición desde la que se decide. Todo ello en
  [14 §5](14-rendimiento-y-coste.md#5-lo-que-el-culler-ahorra-ahora-y-lo-que-no).

  Con un desfase que no es nuestro gusto sino un hecho: **`/imhealth` sigue listando `VIEW_CULLING` entre
  sus huecos declarados**, con la frase que dice que el culler no tiene bucle. Esa línea ya no es cierta, y
  la lista de huecos vive en el código — así que corregirla es un cambio de código. Ver
  [13 §3](13-estado-del-servidor.md#3-las-tres-cifras-que-el-informe-declara-desconocidas).
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
- **La reacción al terreno de un vehículo.** Las cuatro clases de `VehicleKind` existen, los techos de
  ángulo se escalan por lo que el vehículo está haciendo de verdad, el escalón se reparte en su duración, y
  los 20 valores de `vehicle.yml: terrain.*` se leen y se validan. Dos límites honestos, los dos arriba: el
  roll no se dibuja y el escalón necesita NMS. Ver [06 §4 y §5](06-vehiculos.md).
- **El movimiento en el sitio de un vehículo, y su rotación mientras se mueve.** Dos cosas que antes no
  llegaban a la pantalla. `moveLocal` está implementado sobre los internos del servidor, así que un paso
  ordinario es una escritura con colisión y no un teleport programado — y la capacidad **se sondea** en vez
  de asumirse, porque un `true` equivocado lanzaría una vez por vehículo y por tick desde dentro del paso de
  una región. Y el empujón de rotación ya **no** está condicionado a que el vehículo esté quieto: lo estaba,
  y como antes todo paso con desplazamiento era un teleport, la inclinación y el cabeceo de un vehículo eran
  visibles **solo aparcado**. Ver
  [06 §11](06-vehiculos.md#la-rotación-sobrevive-a-un-vehículo-en-movimiento).
- **El coste de todo esto, acotado por configuración.** La sonda de terreno —lo único del plugin que lee
  bloques, y lo único que no se puede sacar del tick— tiene cinco claves de muestreo, así que un vehículo
  aparcado lee el suelo una vez cada dos segundos en vez de veinte veces por segundo. Ver
  [14](14-rendimiento-y-coste.md).
- **Comportamiento con estado sobre un vehículo.** `VehicleController` se entrega en el `VehicleSetup`, se
  adapta sobre el mecanismo por asiento —así que los dos ven el mismo `SeatInput`— y se contiene,
  throttlea y desengancha con las dos claves de `input.yml` que ya usaba un handler de entrada. Ver
  [12](12-controlador-de-vehiculo.md).
- **Conducir un vehículo con un plan de IA.** `VehicleBinder.createPiloted(...)` instala un plan en un
  `Mob` que **es** la raíz y sintetiza las teclas del jinete a partir del `AiIntent`, así que un vehículo
  autónomo sigue obedeciendo el perfil, el umbral de teleport y los techos de terreno. Con una tensión que
  hay que leer antes de intentarlo —la raíz tiene que ser un `Mob`, y el consejo de autoridad de
  [06 §1](06-vehiculos.md) dice que elijas un `ArmorStand`— explicada entera en
  [07 §10](07-ia.md#10-conducir-un-vehículo-con-un-plan).
- **Preguntarle al plugin qué está haciendo.** `/imhealth` y `/imstats`, siete partes y catorce cifras, con
  las tres que no son medibles **declaradas** en vez de adivinadas. Ver [13](13-estado-del-servidor.md).

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

A esas cuatro se les han sumado las de esta tanda, y conviene decirlas: la sonda de terreno —dos columnas
de bloques, una de ellas posiblemente de otra región— nunca se ha leído en un servidor; el empuje de
rotación de modelo completo tampoco; **el `moveLocal` del adaptador tampoco se ha ejecutado nunca**, así que
que el escalón se vea sigue siendo una afirmación sobre el código y no una observación; y de la capa de
vehículos sigue sin poder probarse nada que dependa de un asiento, porque ningún modelo de este repositorio
tiene locator. Las cifras de los ejemplos de [06](06-vehiculos.md) están **elegidas, no medidas**.

Y lo mismo vale para el rendimiento: las cifras de [14](14-rendimiento-y-coste.md) son **asignaciones
medidas por el contador del JVM desde la suite de tests**, más recuentos de llamadas. **Ni un milisegundo de
tick, ni un paquete, ni una lectura de bloque real se ha medido en ninguna parte de este proyecto.**

Las constantes cosméticas de la marcha — amplitudes, balanceo, respiración, los ángulos del tobillo —
fueron **elegidas, no medidas**, y nunca se han visto sobre un modelo. Son claves de `gait.yml`, así que
ajustarlas es editar un YAML, no recompilar. [05](05-fisica-y-movimiento.md) dice exactamente cuáles.
