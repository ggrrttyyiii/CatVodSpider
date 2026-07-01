#!/bin/bash

#####################################################################
# JAR 精简脚本 - Optimize JAR file size
# 功能: 移除不必要的元数据、文档、测试文件等
# Usage: bash slim-jar.sh <input.jar> [output.jar]
#####################################################################

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 函数: 打印彩色信息
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查参数
if [ $# -lt 1 ]; then
    print_error "Usage: $0 <input.jar> [output.jar]"
    echo "Example: $0 aidaox.jar aidaox-slim.jar"
    exit 1
fi

INPUT_JAR="$1"
OUTPUT_JAR="${2:-${INPUT_JAR%.jar}-slim.jar}"
EXTRACT_DIR="jar_extract_$$"

# 检查输入文件
if [ ! -f "$INPUT_JAR" ]; then
    print_error "Input JAR file not found: $INPUT_JAR"
    exit 1
fi

# 获取原始大小
ORIGINAL_SIZE=$(du -h "$INPUT_JAR" | cut -f1)
ORIGINAL_SIZE_BYTES=$(stat -f%z "$INPUT_JAR" 2>/dev/null || stat -c%s "$INPUT_JAR" 2>/dev/null)

print_info "开始精简JAR文件..."
print_info "输入文件: $INPUT_JAR (大小: $ORIGINAL_SIZE)"
print_info "输出文件: $OUTPUT_JAR"
echo ""

# 1. 提取JAR
print_info "第1步: 提取JAR文件..."
mkdir -p "$EXTRACT_DIR"
unzip -q "$INPUT_JAR" -d "$EXTRACT_DIR"
print_success "JAR文件已提取到 $EXTRACT_DIR"

# 2. 移除不必要的文件
print_info "第2步: 移除不必要的文件..."

# 移除Maven信息
if [ -d "$EXTRACT_DIR/META-INF/maven" ]; then
    print_info "  - 删除 META-INF/maven (Maven信息)"
    rm -rf "$EXTRACT_DIR/META-INF/maven"
fi

# 移除备份文件
print_info "  - 删除备份文件 (*.bak, *~)"
find "$EXTRACT_DIR" -type f \( -name "*.bak" -o -name "*~" \) -delete

# 移除文档
print_info "  - 删除文档文件"
rm -rf "$EXTRACT_DIR/doc" "$EXTRACT_DIR/docs" "$EXTRACT_DIR/documentation"
find "$EXTRACT_DIR" -type f \( -name "README*" -o -name "CHANGELOG*" -o -name "LICENSE*" \) -delete

# 移除示例
print_info "  - 删除示例代码"
rm -rf "$EXTRACT_DIR/examples" "$EXTRACT_DIR/example" "$EXTRACT_DIR/samples"

# 移除测试资源
print_info "  - 删除测试资源"
rm -rf "$EXTRACT_DIR/test" "$EXTRACT_DIR/tests" "$EXTRACT_DIR/testing"

# 3. 显示文件统计
print_info "第3步: 文件统计..."
TOTAL_FILES=$(find "$EXTRACT_DIR" -type f | wc -l)
CLASS_FILES=$(find "$EXTRACT_DIR" -name "*.class" -type f | wc -l)
PROPERTY_FILES=$(find "$EXTRACT_DIR" -name "*.properties" -type f | wc -l)

print_info "  - 总文件数: $TOTAL_FILES"
print_info "  - Class文件数: $CLASS_FILES"
print_info "  - Properties文件数: $PROPERTY_FILES"
echo ""

# 4. 显示最大的文件
print_info "第4步: 最大的20个文件:"
find "$EXTRACT_DIR" -type f -exec du -h {} \; | sort -rh | head -20 | sed 's/^/  - /'
echo ""

# 5. 重新打包
print_info "第5步: 重新打包JAR文件..."
cd "$EXTRACT_DIR"

# 创建新JAR，使用最大压缩率
if command -v zip &> /dev/null; then
    zip -r -9 -q "../$OUTPUT_JAR" .
else
    print_error "zip命令未找到，请先安装 zip 工具"
    cd ..
    rm -rf "$EXTRACT_DIR"
    exit 1
fi

cd ..
print_success "JAR文件已重新打包"

# 6. 清理临时文件
print_info "第6步: 清理临时文件..."
rm -rf "$EXTRACT_DIR"
print_success "临时文件已删除"

# 7. 显示压缩结果
NEW_SIZE=$(du -h "$OUTPUT_JAR" | cut -f1)
NEW_SIZE_BYTES=$(stat -f%z "$OUTPUT_JAR" 2>/dev/null || stat -c%s "$OUTPUT_JAR" 2>/dev/null)

SAVED_BYTES=$((ORIGINAL_SIZE_BYTES - NEW_SIZE_BYTES))
if [ $ORIGINAL_SIZE_BYTES -gt 0 ]; then
    REDUCTION_PERCENT=$((SAVED_BYTES * 100 / ORIGINAL_SIZE_BYTES))
else
    REDUCTION_PERCENT=0
fi

echo ""
echo "=========================================="
print_success "精简完成！"
echo "=========================================="
echo "原始大小: $ORIGINAL_SIZE (${ORIGINAL_SIZE_BYTES} 字节)"
echo "精简后:   $NEW_SIZE (${NEW_SIZE_BYTES} 字节)"
echo "节省:     $(echo "scale=2; $SAVED_BYTES / 1024 / 1024" | bc) MB"
echo "压缩率:   $REDUCTION_PERCENT%"
echo "输出文件: $OUTPUT_JAR"
echo "=========================================="
echo ""

print_success "完成！精简后的JAR已保存到: $OUTPUT_JAR"
