#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成应用图标：深色圆角底 + 蓝色定位针（输出各密度）。"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def rounded_mask(size, radius):
    m = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def gradient(size, top=(21, 34, 64), bottom=(11, 18, 32)):
    img = Image.new('RGB', (size, size), top)
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (size, y)], fill=c)
    return img


def draw_pin(img, size):
    d = ImageDraw.Draw(img)
    s = size
    cx = s * 0.5
    cy = s * 0.40
    r = s * 0.17
    # 针体（水滴）
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(79, 140, 255))
    d.polygon([(cx - r * 0.78, cy + r * 0.62), (cx + r * 0.78, cy + r * 0.62),
               (cx, cy + r * 2.15)], fill=(79, 140, 255))
    # 内圈
    r2 = r * 0.42
    d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=(11, 18, 32))
    # 地面阴影椭圆
    d.ellipse([cx - r * 0.75, cy + r * 2.25, cx + r * 0.75, cy + r * 2.55],
              fill=(30, 49, 84))
    return img


def main():
    for density, size in SIZES.items():
        img = gradient(size)
        draw_pin(img, size)
        mask = rounded_mask(size, int(size * 0.22))
        out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        d = os.path.join(ROOT, 'res', 'mipmap-' + density)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, 'ic_launcher.png')
        out.save(path)
        print('wrote', path, out.size)


if __name__ == '__main__':
    main()
