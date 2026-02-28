# TODO

## Navigation / UX
- [ ] Navigationsbaum vereinfachen: flache Struktur ohne redundante Kategorieknoten
      (Exceltools/Mergescripte entfernen, direkt Excel Split / MERGE Generator / Script ausführen als Knoten)
      Trennlinie zwischen Tools und Einstellungen als visueller Separator

## Testing
- [ ] Docker installiert → Oracle XE als Testdatenbank aufsetzen (HR-Schema mit echten FKs und Sequences)
      Befehl: `docker run -d -p 1521:1521 gvenzl/oracle-xe --env ORACLE_PASSWORD=test`
      Dann MigrationTool gegen HR-Schema testen

## Infrastruktur
- [x] Lokales Git-Repo eingerichtet (.gitignore, initialer Commit mit 47 Dateien)
- [x] GitHub-Account anlegen
- [x] Privates Repo `MigrationTool` auf GitHub erstellt
- [x] Remote gesetzt und gepusht: https://github.com/maddipp1984-cpu/MigrationTool
