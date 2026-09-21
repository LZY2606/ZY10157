package com.local.spectrum.service;

final class Geometry {
  private Geometry() {
  }

  static double overlap(double lowA, double highA, double lowB, double highB) {
    double low = Math.max(lowA, lowB);
    double high = Math.min(highA, highB);
    return low < high ? high - low : 0.0d;
  }

  static long overlap(long lowA, long highA, long lowB, long highB) {
    long low = Math.max(lowA, lowB);
    long high = Math.min(highA, highB);
    return low < high ? high - low : 0L;
  }

  static long gap(long lowA, long highA, long lowB, long highB) {
    if (overlap(lowA, highA, lowB, highB) > 0) {
      return 0L;
    }
    return highA <= lowB ? lowB - highA : lowA - highB;
  }
}
