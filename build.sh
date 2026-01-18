#!/bin/bash

# Build script para NoNightmare Plugin

set -e

echo "=== NoNightmare Plugin Build ==="
echo ""

# Clean
echo "Cleaning..."
rm -rf build libs NoNightmare*.jar

# Create directories
mkdir -p build/classes/java/main

# Compile
echo "Compiling Java files..."
cd src/main/java
find . -name "*.java" | while read f; do
    echo "  Compiling: $f"
done
cd /c/Users/prahzera/IdeaProjects/NoNightamare
javac -cp lib/HytaleServer.jar -d build/classes/java/main $(find src/main/java -name "*.java")

# Copy resources
echo "Copying resources..."
cp -r src/main/resources/* build/classes/java/main/

# Create JAR
echo "Creating JAR..."
jar cf NoNightmare-1.0.jar -C build/classes/java/main .

# Verify
echo ""
echo "=== Build Complete ==="
ls -lh NoNightmare-1.0.jar
echo ""
echo "✓ Plugin JAR: NoNightmare-1.0.jar"
echo "✓ Ready to deploy to mods/ folder"
