import UIKit
import SwiftUI
import Shared

/**
 * iOS 宿主：把 shared 模块里的 Compose 界面包成 SwiftUI 视图。
 * 构建需要 macOS + Xcode（Kotlin/Native 的 Apple 产物只能在 macOS 上链接）。
 */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // MainViewControllerKt 由 shared 模块导出
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
