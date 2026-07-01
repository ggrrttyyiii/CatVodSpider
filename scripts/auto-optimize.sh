#!/bin/bash
# 自动生成精简JAR的辅助脚本
# 这个脚本用于在CI/CD环境中自动优化JAR文件

set -e

echo "🚀 开始JAR精简流程..."
echo "=================================================="

# 检查文件
if [ ! -f "jar/aidaox.jar" ]; then
    echo "❌ 错误: 找不到 jar/aidaox.jar"
    exit 1
fi

# 创建临时目录
TEMP_DIR="jar_optimization_temp"
mkdir -p "$TEMP_DIR"

echo "📥 第1步: 提取JAR文件..."
cd "$TEMP_DIR"
unzip -q ../jar/aidaox.jar
cd ..

echo "🧹 第2步: 清理不必要的文件..."
# 移除Maven元数据
rm -rf "$TEMP_DIR/META-INF/maven" 2>/dev/null || true

# 删除备份文件
find "$TEMP_DIR" -type f \( -name "*.bak" -o -name "*~" \) -delete 2>/dev/null || true

# 删除文档
rm -rf "$TEMP_DIR/doc" "$TEMP_DIR/docs" "$TEMP_DIR/documentation" 2>/dev/null || true
find "$TEMP_DIR" -type f \( -name "README*" -o -name "CHANGELOG*" -o -name "LICENSE*" \) -delete 2>/dev/null || true

# 删除示例
rm -rf "$TEMP_DIR/examples" "$TEMP_DIR/example" "$TEMP_DIR/samples" 2>/dev/null || true

# 删除测试资源
rm -rf "$TEMP_DIR/test" "$TEMP_DIR/tests" "$TEMP_DIR/testing" 2>/dev/null || true

echo "📦 第3步: 重新打包JAR..."
cd "$TEMP_DIR"
zip -r -9 -q ../jar/aidaox-slim.jar . 2>/dev/null || zip -r -9 ../jar/aidaox-slim.jar .
cd ..

echo "📊 第4步: 统计结果..."
ORIGINAL=$(stat -c%s jar/aidaox.jar 2>/dev/null || stat -f%z jar/aidaox.jar 2>/dev/null)
SLIM=$(stat -c%s jar/aidaox-slim.jar 2>/dev/null || stat -f%z jar/aidaox-slim.jar 2>/dev/null)
SAVED=$((ORIGINAL - SLIM))
PERCENT=$((SAVED * 100 / ORIGINAL))

echo ""
echo "✅ JAR精简完成！"
echo "=================================================="
echo "原始大小: $((ORIGINAL / 1024 / 1024)) MB"
echo "精简后:  $((SLIM / 1024 / 1024)) MB"
echo "节省:    $((SAVED / 1024 / 1024)) MB ($PERCENT%)"
echo "=================================================="

echo "🧹 第5步: 清理临时文件..."
rm -rf "$TEMP_DIR"

echo "✨ 完成！精简JAR已生成: jar/aidaox-slim.jar"
