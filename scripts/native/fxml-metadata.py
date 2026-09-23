#!/usr/bin/env python3
"""Generate only main.fxml's named injection fields and no-argument event handlers.

This is intentionally not a general Java/FXML parser. Fail if a handler changes signature; review
and extend the generator rather than registering the controller's entire reflection surface.
"""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'src/native/resources/META-INF/native-image/com.editora/fxml/reachability-metadata.json'


def generate():
    tree = ET.parse(ROOT / 'src/main/resources/com/editora/ui/main.fxml')
    fx = '{http://javafx.com/fxml}'
    controller = tree.getroot().attrib[fx + 'controller']
    source = (ROOT / 'src/main/java' / (controller.replace('.', '/') + '.java')).read_text()
    fields, methods = set(), set()
    for node in tree.iter():
        if fx + 'id' in node.attrib:
            name = node.attrib[fx + 'id']
            if not re.search(r'@FXML\s+private\s+[\w.<>]+\s+' + re.escape(name) + r'\s*;', source):
                raise ValueError('Review changed injection field: ' + name)
            fields.add(name)
        for value in node.attrib.values():
            if value.startswith('#'):
                name = value[1:]
                if not re.search(r'@FXML\s+private\s+void\s+' + re.escape(name) + r'\s*\(\s*\)', source):
                    raise ValueError('Review changed event signature: ' + name)
                methods.add(name)
    result = {'reflection': [{'condition': {'typeReached': 'javafx.fxml.FXMLLoader'}, 'type': controller,
                            'fields': [{'name': name} for name in sorted(fields)],
                            'methods': [{'name': name, 'parameterTypes': []} for name in ['<init>'] + sorted(methods)]}]}
    # These FXML element types are looked up from import processing, then populated through beans.
    # Application controllers remain restricted to exact named members above.
    fxml = (ROOT / 'src/main/resources/com/editora/ui/main.fxml').read_text()
    for name in sorted(set(re.findall(r'<\?import ([\w.]+)\?>', fxml))):
        result['reflection'].append({'condition': {'typeReached': 'javafx.fxml.FXMLLoader'},
                                     'type': name, 'allPublicMethods': True,
                                     'methods': [{'name': '<init>', 'parameterTypes': []}]})
    # BeanAdapter walks declared methods on the layout superclass chain of the
    # imported panes, even though those superclasses are not FXML elements.
    for name in ('javafx.scene.Node', 'javafx.scene.Parent', 'javafx.scene.layout.Region',
                 'javafx.scene.layout.Pane'):
        result['reflection'].append({'condition': {'typeReached': 'javafx.fxml.FXMLLoader'},
                                     'type': name, 'queryAllDeclaredMethods': True})
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--check', action='store_true')
    args = p.parse_args()
    expected = json.dumps(generate(), indent=2) + '\n'
    if args.check:
        if not DEST.exists() or DEST.read_text() != expected:
            p.error('FXML metadata drift; regenerate and review the diff')
    else:
        DEST.parent.mkdir(parents=True, exist_ok=True)
        DEST.write_text(expected)


if __name__ == '__main__':
    main()
