package com.ts.can.carinfo;

interface ICarInfoService {
    int requestCarAirInfo(String str);
    String requestCarAirLtTemp();
    String requestCarAirRtTemp();
    int[] requestCarDoorInfo();
    boolean requestCarIllInfo();
    int[] requestCarBaseInfo();
    int[] requestT3FlDevInfo();
    int requestT3FlSta();
    int[] requestT3FlCanData7f1();
    int[] requestT3FlCanData7f2();
    int[] requestT3FlCanData7f3();
    int[] requestT3FlCanData7f4();
    int[] requestT3FlCanData7e0();
    int[] requestT3FlTexlData();
    int T3FlTexlCmd(int type, in int[] cmd);
    int[] requestT3FlTexlDisCur();
    int[] requestT3FlTexlDisOver();
}
