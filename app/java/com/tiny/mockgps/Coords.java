package com.tiny.mockgps;

/**
 * 坐标系转换：把输入坐标统一换算成 WGS84（Android LocationManager 使用的坐标系）。
 *  - GCJ-02（火星坐标，高德/腾讯地图）→ WGS84：标准迭代反算
 *  - BD-09（百度坐标）→ GCJ-02 → WGS84
 */
public final class Coords {

    private static final double A = 6378245.0;             // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323; // 偏心率平方
    private static final double X_PI = Math.PI * 3000.0 / 180.0;

    private Coords() {
    }

    public static double[] toWgs84(double lng, double lat, int type) {
        switch (type) {
            case 1: // GCJ-02
                return gcj02ToWgs84(lng, lat);
            case 2: // BD-09
                double[] gcj = bd09ToGcj02(lng, lat);
                return gcj02ToWgs84(gcj[0], gcj[1]);
            default: // WGS84
                return new double[]{lng, lat};
        }
    }

    public static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double[] approx = new double[]{lng, lat};
        for (int i = 0; i < 12; i++) {
            double[] g = wgs84ToGcj02(approx[0], approx[1]);
            double dLng = g[0] - lng;
            double dLat = g[1] - lat;
            if (Math.abs(dLng) < 1e-9 && Math.abs(dLat) < 1e-9) {
                break;
            }
            approx[0] -= dLng;
            approx[1] -= dLat;
        }
        return approx;
    }

    public static double[] gcj02ToBd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    public static double[] bd09ToGcj02(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.cos(theta), z * Math.sin(theta)};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
