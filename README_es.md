# ShapeBoard

*Read this in [English](README.md).*

**Scoreboards para zonas de CUALQUIER forma, no solo cajas.** Delimita tu excavación, perímetro o zona de construcción con una línea de bloques en el cielo, corre un comando, y ShapeBoard cuenta cada bloque roto y colocado dentro de esa forma exacta. Cuando alguien entra, le aparece un sidebar con el leaderboard solo a él. Cuando sale, desaparece.

100% server-side. Los jugadores no instalan nada.

## Demo

![Al salir de la zona el sidebar se quita, al volver a entrar vuelve, con los scores en vivo](docs/demo.gif)

Video en calidad completa con sonido: [shapeboard-demo.mp4](https://github.com/CodeW4VE/ShapeBoard/releases/download/v1.0.1/shapeboard-demo.mp4)

## Por qué

Herramientas como buildevents solo soportan cajas rectangulares. Los proyectos reales no son rectángulos: los perímetros tienen esquinas redondeadas, ríos que quieres excluir, bultos raros. Si trackeas una caja alrededor de un perímetro redondo, la gente que pica fuera del proyecto también sube en tu leaderboard. ShapeBoard sigue el contorno exacto que dibujaste, así que el conteo es justo.

El sidebar además es por jugador de verdad. Se manda con paquetes de scoreboard dirigidos, así que solo lo ve la gente que está dentro de la forma. No se tocan teams, no se cambian colores de team, y dos jugadores que compartan color de team no se filtran el board entre sí. Lo que sea que tu server haga con teams y colores queda intacto.

## Instalación

1. Server Fabric para Minecraft 1.21 con [Fabric API](https://modrinth.com/mod/fabric-api).
2. Suelta `shapeboard-x.y.z+1.21.jar` en `mods/`.
3. Reinicia. Listo: no hay config que editar.

## Inicio rápido

Digamos que TVTvirus es admin y quiere trackear la excavación del perímetro de su server:

1. **Dibuja el contorno.** Vuela a una Y por encima de la obra (por ejemplo y150) y dibuja una línea continua de un bloque marcador (por ejemplo black concrete) siguiendo la forma del proyecto, hasta que cierre sobre sí misma. Vale cualquier forma: círculos, manchas, agujeros de dona, esquinas cortadas. Los huecos de hasta 6 bloques se puentean solos; los más grandes hacen fallar el escaneo y te dice las coordenadas exactas de los extremos abiertos para que vayas a parcharlos.

2. **Crea la shape.** Párate a menos de 64 bloques de la línea (o pasa coordenadas explícitas desde la consola) y corre:

   ```
   /shapeboard create bigculo minecraft:black_concrete 150
   ```

   ShapeBoard camina toda la línea, cierra la forma y reporta el área:

   ```
   [ShapeBoard] Shape 'bigculo' created in 45 ms: 797,680 columns inside,
   outline of 4,350 blocks. Objectives: bigculo_break / bigculo_place.
   ```

3. **Ponle un nombre bonito.** Es lo que los jugadores ven en el sidebar:

   ```
   /shapeboard rename bigculo Big Culo
   ```

4. **Ya está.** Desde ese momento, todo lo que se pique o coloque dentro de la forma y por debajo de la línea marcadora cuenta en los objetivos vanilla de scoreboard `bigculo_break` y `bigculo_place`. A quien entre le sale:

   ```
   [ShapeBoard] You entered Big Culo. Blocks you mine or place here count toward the leaderboard.
   [ShapeBoard] Sidebar enabled. Run /shapeboard hide to hide it.
   ```

   con un sidebar top 15 en vivo. Si no están en el top 15 pero tienen score, su propia línea se muestra en el último hueco para que siempre sepan por dónde van.

## Comandos

| Comando | Quién | Qué hace |
|---|---|---|
| `/shapeboard create <id> <bloque> <y> [x z]` | OP | Escanea la línea marcadora y crea la shape. `x z` punto de partida opcional (obligatorio desde consola) |
| `/shapeboard rename <id> <nombre...>` | OP | Cambia el nombre que se muestra en el sidebar |
| `/shapeboard metric <id> <break\|place\|both>` | OP | Por qué rankea el leaderboard: bloques rotos (default), colocados, o la suma. Perfecto para zonas de construcción/decoración |
| `/shapeboard delete <id>` | OP | Borra la shape (los objetivos de scoreboard se conservan) |
| `/shapeboard list` | todos | Lista las shapes con área e info del marcador |
| `/shapeboard info <id>` | todos | Detalles de una shape |
| `/shapeboard top [id]` | todos | Top 10 + totales en el chat |
| `/shapeboard hide` / `show` | todos | Toggle del sidebar por jugador, se recuerda entre sesiones |
| `/shapeboard contains <id> <x> <z>` | OP | Debug: ¿esta columna está dentro de la forma? |

## Cómo funciona

- **Escaneo:** partiendo de un bloque semilla, ShapeBoard camina la línea conectada de bloques marcadores a esa Y exacta (cargando solo los chunks por los que pasa la línea), puentea huecos de hasta 6 bloques, y corre un flood fill desde afuera. Todo lo que el flood no alcanza está dentro. Una línea diagonal bloquea el fill correctamente, así que los contornos con esquinas a 45 grados van bien.
- **Máscara:** el resultado se guarda como intervalos de z por columna de x en `world/shapeboard/shapes.json`. Un lookup son un par de comparaciones de enteros, así que el tracking no cuesta nada ni con docenas de jugadores picando.
- **Conteo:** los bloques rotos se cuentan con el evento de Fabric, los colocados con un mixin chiquito. Solo cuentan las columnas dentro de la forma y por debajo de la Y del marcador. Los scores van a objetivos vanilla de scoreboard, así que todo lo demás de tu server (comandos, datapacks, otros mods) puede leerlos.
- **Sidebar:** cada espectador recibe un objetivo falso solo-cliente por paquetes dirigidos (`shapeboard_view`), refrescado solo cuando los números cambian. Al salir de la zona se elimina y se restaura el objetivo de sidebar que el server tuviera puesto. Los nombres en el board conservan sus colores de team.

## Preguntas frecuentes

**¿Necesita algo en el cliente?** No. Los jugadores vanilla ven todo.

**¿Y el TNT?** Los bloques rotos por explosiones no se acreditan a nadie (el juego no los atribuye a un jugador). Picar a mano y con herramientas cuenta normal.

**¿Cuentan los bots de carpet?** Sus breaks y placements cuentan bajo el nombre del propio bot, así que una excavación llena de bots igual muestra quién corrió qué. Los bots nunca reciben sidebars ni mensajes.

**¿Varias shapes?** Sí, crea las que quieras. Un jugador parado dentro de una ve el board de esa.

**¿Y si mi contorno tiene un agujero?** El escaneo falla e imprime las coordenadas de los extremos abiertos de la línea. Párchalos (o deja hasta 6 bloques, que se puentean solos) y vuelve a correr create.

**¿Puedo trackear una zona de construcción o decoración que no tiene contorno?** Sí: la línea marcadora solo hace falta durante el escaneo. Dibuja un contorno temporal con cualquier bloque barato a una Y por encima de la obra, corre create, y borra los bloques: la shape sigue funcionando para siempre (la máscara queda guardada en `world/shapeboard/`). Después pon `/shapeboard metric <id> place` para que el leaderboard rankee por bloques colocados en vez de picados (o `both` para la suma).

**¿Puedo trackear una zona donde ya se picó antes?** Los objetivos arrancan en cero al crear la shape. Si tienes números previos en otro objetivo, cópialos con `/scoreboard players operation`.

**¿El escaneo lagea el server?** Solo carga los chunks que toca el contorno, una vez, al crear. Un contorno de 4,000 bloques tarda bastante menos de un segundo en un mundo cargado.

## Compilar desde el código

```
./gradlew build
```

El jar cae en `build/libs/`. Java 21+, Gradle descarga todo lo demás.

## Licencia

MIT
